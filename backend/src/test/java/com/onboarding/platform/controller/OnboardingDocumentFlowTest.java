package com.onboarding.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onboarding.platform.client.AiServiceClient;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.exception.DocumentUnreadableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upload -> review -> confirm flow against in-memory H2, with the Python AI service mocked out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingDocumentFlowTest {

    private static final MockMultipartFile IMAGE =
            new MockMultipartFile("file", "passport.jpg", "image/jpeg", new byte[]{1, 2, 3});

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiServiceClient aiServiceClient;

    @Test
    void cleanPassport_uploadThenConfirm_isAutoApproved() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000001", 0.95, true, "2031-05-01");

        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.documentType").value("PASSPORT"))
                .andExpect(jsonPath("$.fields.surname").value("BENALI"))
                .andExpect(jsonPath("$.fields.field_confidence").doesNotExist())
                .andExpect(jsonPath("$.fieldConfidence.surname").value(0.95))
                .andExpect(jsonPath("$.documentNumber").value("P1000001"))
                .andExpect(jsonPath("$.warnings", empty()))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"));

        mockMvc.perform(get("/api/onboarding/sessions/" + sessionId + "/document")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.given_names").value("SAMI"));

        confirm(token, sessionId, Map.of("surname", "BENALI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.userCorrected").value(false));

        // Confirming twice is a state conflict.
        confirm(token, sessionId, Map.of())
                .andExpect(status().isConflict());
    }

    @Test
    void lowConfidenceAndFailedChecksum_areFlaggedForReview() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000002", 0.35, false, "2031-05-01");

        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasItem("LOW_CONFIDENCE")))
                .andExpect(jsonPath("$.warnings", hasItem("CHECKSUM_FAILED")))
                .andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW"));
    }

    @Test
    void singleWeakField_blocksAutoApproval_evenWithHighOverallConfidence() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000009", 0.95, true, "2031-05-01", Map.of(
                "document_number", 0.95, "surname", 0.3, "given_names", 0.95, "nationality", 0.95,
                "date_of_birth", 0.95, "sex", 0.95, "expiry_date", 0.95));

        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldConfidence.surname").value(0.3))
                .andExpect(jsonPath("$.warnings", hasItem("LOW_CONFIDENCE")))
                .andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW"));

        confirm(token, sessionId, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("PENDING_REVIEW"));
    }

    @Test
    void userCorrection_isPersistedAndBlocksAutoApproval() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000003", 0.95, true, "2031-05-01");
        upload(token, sessionId, "PASSPORT").andExpect(status().isOk());

        confirm(token, sessionId, Map.of("given_names", "SAMY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.given_names").value("SAMY"))
                .andExpect(jsonPath("$.userCorrected").value(true))
                .andExpect(jsonPath("$.warnings", hasItem("USER_CORRECTED")))
                .andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.sessionStatus").value("PENDING_REVIEW"));
    }

    @Test
    void confirm_rejectsUnknownFieldsAndBadDates() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000004", 0.95, true, "2031-05-01");
        upload(token, sessionId, "PASSPORT").andExpect(status().isOk());

        confirm(token, sessionId, Map.of("favourite_colour", "blue"))
                .andExpect(status().isBadRequest());
        confirm(token, sessionId, Map.of("field_confidence", "1.0"))
                .andExpect(status().isBadRequest());
        confirm(token, sessionId, Map.of("date_of_birth", "11/03/1995"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void expiredDocument_isFlagged() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000005", 0.95, true, "2020-01-01");

        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasItem("DOCUMENT_EXPIRED")));
    }

    @Test
    void unreadableImage_returns422_andRetakeWorks() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        when(aiServiceClient.extract(eq(DocumentType.PASSPORT), any(), any(), any(), any()))
                .thenThrow(new DocumentUnreadableException("Could not locate a machine-readable zone."));

        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isUnprocessableEntity());

        // Session is untouched, so the user can simply retake.
        mockPassport("P1000006", 0.95, true, "2031-05-01");
        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentNumber").value("P1000006"));

        // Retake again before confirming replaces the document.
        mockPassport("P1000007", 0.95, true, "2031-05-01");
        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentNumber").value("P1000007"));
    }

    @Test
    void sameDocumentNumberOnAnotherApplication_isRejected() throws Exception {
        mockPassport("P1000008", 0.95, true, "2031-05-01");

        String firstToken = registerAndGetToken();
        upload(firstToken, createSessionWithConsent(firstToken), "PASSPORT").andExpect(status().isOk());

        String secondToken = registerAndGetToken();
        upload(secondToken, createSessionWithConsent(secondToken), "PASSPORT")
                .andExpect(status().isConflict());
    }

    @Test
    void uploadBeforeConsent_isRejected() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSession(token);

        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isConflict());
    }

    @Test
    void otherUsersSession_isNotFound() throws Exception {
        String ownerToken = registerAndGetToken();
        String sessionId = createSessionWithConsent(ownerToken);

        String otherToken = registerAndGetToken();
        mockMvc.perform(get("/api/onboarding/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        upload(otherToken, sessionId, "PASSPORT")
                .andExpect(status().isNotFound());
    }

    @Test
    void nonImageUpload_isRejected() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        MockMultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1});

        mockMvc.perform(multipart("/api/onboarding/sessions/" + sessionId + "/document")
                        .file(pdf)
                        .param("documentType", "PASSPORT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cin_needsBothSides_andMergesThemIntoOneDocument() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockCinFront("11000001", 0.9);
        mockCinBack(0.85);

        // Back first is refused: there is no front to attach it to.
        upload(token, sessionId, "CIN", "BACK").andExpect(status().isConflict());

        upload(token, sessionId, "CIN", "FRONT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backSideRequired").value(true))
                .andExpect(jsonPath("$.backSideCaptured").value(false))
                .andExpect(jsonPath("$.fields.last_name").value("بن سالم"));

        // Confirming without the back is refused.
        confirm(token, sessionId, Map.of()).andExpect(status().isConflict());

        upload(token, sessionId, "CIN", "BACK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backSideCaptured").value(true))
                .andExpect(jsonPath("$.fields.last_name").value("بن سالم"))
                .andExpect(jsonPath("$.fields.address").value("12 نهج الحرية صفاقس"))
                .andExpect(jsonPath("$.fieldConfidence.address").value(0.85))
                .andExpect(jsonPath("$.fieldConfidence.last_name").value(0.9))
                // the document is as confident as its weakest field, across both sides
                .andExpect(jsonPath("$.ocrConfidence").value(0.85));

        confirm(token, sessionId, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("APPROVED"));
    }

    @Test
    void cin_retakingTheFront_keepsTheBack() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockCinFront("11000002", 0.9);
        mockCinBack(0.85);
        upload(token, sessionId, "CIN", "FRONT").andExpect(status().isOk());
        upload(token, sessionId, "CIN", "BACK").andExpect(status().isOk());

        mockCinFront("11000003", 0.95);
        upload(token, sessionId, "CIN", "FRONT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentNumber").value("11000003"))
                .andExpect(jsonPath("$.backSideCaptured").value(true))
                .andExpect(jsonPath("$.fields.address").value("12 نهج الحرية صفاقس"))
                .andExpect(jsonPath("$.fieldConfidence.address").value(0.85));
    }

    @Test
    void passport_hasNoBackSide() throws Exception {
        String token = registerAndGetToken();
        String sessionId = createSessionWithConsent(token);
        mockPassport("P1000010", 0.95, true, "2031-05-01");
        upload(token, sessionId, "PASSPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backSideRequired").value(false));

        upload(token, sessionId, "PASSPORT", "BACK").andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private void mockCinFront(String number, double confidence) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("document_number", number);
        fields.put("last_name", "بن سالم");
        fields.put("first_name", "أمين");
        fields.put("lineage", "بن محمد بن صالح");
        fields.put("date_of_birth", "1996-09-14");
        fields.put("place_of_birth", "صفاقس");
        fields.put("overall_confidence", confidence);
        fields.put("field_confidence", Map.of(
                "document_number", confidence, "last_name", confidence, "first_name", confidence,
                "lineage", confidence, "date_of_birth", confidence, "place_of_birth", confidence));
        when(aiServiceClient.extract(eq(DocumentType.CIN), eq(DocumentSide.FRONT), any(), any(), any()))
                .thenReturn(objectMapper.valueToTree(fields));
    }

    private void mockCinBack(double confidence) {
        JsonNode result = objectMapper.valueToTree(Map.of(
                "profession", "مهندس",
                "address", "12 نهج الحرية صفاقس",
                "issue_date", "2018-03-03",
                "overall_confidence", confidence,
                "field_confidence", Map.of("profession", confidence, "address", confidence, "issue_date", confidence)));
        when(aiServiceClient.extract(eq(DocumentType.CIN), eq(DocumentSide.BACK), any(), any(), any()))
                .thenReturn(result);
    }

    private void mockPassport(String number, double confidence, boolean checksumValid, String expiry) {
        Map<String, Double> perField = Map.of(
                "document_number", confidence, "surname", confidence, "given_names", confidence,
                "nationality", confidence, "date_of_birth", confidence, "sex", confidence, "expiry_date", confidence);
        mockPassport(number, confidence, checksumValid, expiry, perField);
    }

    private void mockPassport(String number, double confidence, boolean checksumValid, String expiry,
                              Map<String, Double> fieldConfidence) {
        JsonNode result = objectMapper.valueToTree(Map.of(
                "document_number", number,
                "surname", "BENALI",
                "given_names", "SAMI",
                "nationality", "TUN",
                "date_of_birth", "1995-03-11",
                "sex", "M",
                "expiry_date", expiry,
                "checksum_valid", checksumValid,
                "overall_confidence", confidence,
                "field_confidence", fieldConfidence));
        when(aiServiceClient.extract(eq(DocumentType.PASSPORT), any(), any(), any(), any())).thenReturn(result);
    }

    private ResultActions upload(String token, String sessionId, String documentType) throws Exception {
        return upload(token, sessionId, documentType, "FRONT");
    }

    private ResultActions upload(String token, String sessionId, String documentType, String side) throws Exception {
        return mockMvc.perform(multipart("/api/onboarding/sessions/" + sessionId + "/document")
                .file(IMAGE)
                .param("documentType", documentType)
                .param("side", side)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions confirm(String token, String sessionId, Map<String, String> fields) throws Exception {
        return mockMvc.perform(post("/api/onboarding/sessions/" + sessionId + "/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("fields", fields))));
    }

    private String registerAndGetToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "email", "flow-" + UUID.randomUUID() + "@example.com",
                "password", "password123"));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String createSession(String token) throws Exception {
        String response = mockMvc.perform(post("/api/onboarding/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createSessionWithConsent(String token) throws Exception {
        String sessionId = createSession(token);
        mockMvc.perform(post("/api/onboarding/sessions/" + sessionId + "/consent")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return sessionId;
    }
}
