package com.onboarding.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onboarding.platform.client.AiServiceClient;
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
        when(aiServiceClient.extract(eq(DocumentType.PASSPORT), any(), any(), any()))
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

    // --- helpers ---

    private void mockPassport(String number, double confidence, boolean checksumValid, String expiry) {
        JsonNode result = objectMapper.valueToTree(Map.of(
                "document_number", number,
                "surname", "BENALI",
                "given_names", "SAMI",
                "nationality", "TUN",
                "date_of_birth", "1995-03-11",
                "sex", "M",
                "expiry_date", expiry,
                "checksum_valid", checksumValid,
                "overall_confidence", confidence));
        when(aiServiceClient.extract(eq(DocumentType.PASSPORT), any(), any(), any())).thenReturn(result);
    }

    private ResultActions upload(String token, String sessionId, String documentType) throws Exception {
        return mockMvc.perform(multipart("/api/onboarding/sessions/" + sessionId + "/document")
                .file(IMAGE)
                .param("documentType", documentType)
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
