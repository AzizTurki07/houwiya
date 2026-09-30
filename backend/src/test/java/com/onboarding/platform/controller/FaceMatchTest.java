package com.onboarding.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.onboarding.platform.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The selfie step: face match + liveness from the (mocked) AI service, and what it does to review. */
@TestPropertySource(properties = "app.face.required=true")
class FaceMatchTest extends ApiTestSupport {

    private static final Map<String, String> FIELDS = Map.of(
            "document_number", "", "surname", "BENALI", "given_names", "SAMI", "nationality", "TUN",
            "date_of_birth", "1995-03-11", "sex", "M", "expiry_date", "2031-01-01");

    private String number;

    @BeforeEach
    void passport() {
        // A fresh number per test: the same number under another account is a duplicate.
        number = "F" + (System.nanoTime() % 10_000_000);
        mockPassport(number, "SAMI", 0.95, true, "2031-01-01");
    }

    private void mockFace(Double similarity, Boolean match, boolean liveness, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_face_found", similarity != null);
        result.put("selfie_face_found", similarity != null);
        result.put("similarity", similarity);
        result.put("match", match);
        result.put("threshold", 0.45);
        result.put("liveness_passed", liveness);
        result.put("liveness_reason", reason);
        JsonNode node = objectMapper.valueToTree(result);
        when(aiServiceClient.verifyFace(any(), any(), anyList())).thenReturn(node);
    }

    private ResultActions selfie(String token, String sessionId, int frames) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/onboarding/sessions/" + sessionId + "/selfie");
        for (int i = 0; i < frames; i++) {
            request.file(new MockMultipartFile("frames", "frame" + i + ".jpg", "image/jpeg", PHOTO));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private Map<String, String> fields(String number) {
        Map<String, String> fields = new LinkedHashMap<>(FIELDS);
        fields.put("document_number", number);
        return fields;
    }

    @Test
    void confirmNeedsTheSelfie() throws Exception {
        String token = register();
        String session = sessionWithConsent(token);
        upload(token, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.selfieRequired").value(true))
                .andExpect(jsonPath("$.selfieCaptured").value(false));
        confirm(token, session, fields(number)).andExpect(status().isConflict());
    }

    @Test
    void selfieNeedsTheDocumentFirstAndThreeFrames() throws Exception {
        String token = register();
        String session = sessionWithConsent(token);
        selfie(token, session, 3).andExpect(status().isNotFound());
        upload(token, session).andExpect(status().isOk());
        selfie(token, session, 1).andExpect(status().isBadRequest());
        verify(aiServiceClient, never()).verifyFace(any(), any(), anyList());
    }

    @Test
    void matchingLiveSelfieIsAutoApproved() throws Exception {
        mockFace(0.72, true, true, null);
        String token = register();
        String session = sessionWithConsent(token);
        upload(token, session).andExpect(status().isOk());
        selfie(token, session, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.selfieCaptured").value(true))
                .andExpect(jsonPath("$.faceMatched").value(true))
                .andExpect(jsonPath("$.livenessPassed").value(true))
                .andExpect(jsonPath("$.warnings").isEmpty())
                // The applicant sees the outcome, never the score.
                .andExpect(jsonPath("$.faceSimilarity").doesNotExist());
        confirm(token, session, fields(number)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));
    }

    @Test
    void mismatchGoesToAReviewerWithTheSelfie() throws Exception {
        mockFace(0.21, false, false, "Turn your head to one side, then to the other.");
        String token = register();
        String session = sessionWithConsent(token);
        upload(token, session).andExpect(status().isOk());
        selfie(token, session, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasItem("FACE_MISMATCH")))
                .andExpect(jsonPath("$.warnings", hasItem("LIVENESS_FAILED")));
        String body = confirm(token, session, fields(number)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("NEEDS_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        String documentId = objectMapper.readTree(body).get("id").asText();

        String admin = loginAdmin();
        mockMvc.perform(get("/api/admin/reviews/" + documentId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faceSimilarity").value(0.21))
                .andExpect(jsonPath("$.faceThreshold").value(0.45))
                .andExpect(jsonPath("$.faceMatched").value(false))
                .andExpect(jsonPath("$.livenessPassed").value(false))
                .andExpect(jsonPath("$.livenessReason").value("Turn your head to one side, then to the other."))
                .andExpect(jsonPath("$.imageSides", hasItem("SELFIE")));
        mockMvc.perform(get("/api/admin/reviews/" + documentId + "/images/SELFIE").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void noFaceOnTheDocumentIsNotVerifiedNotAMismatch() throws Exception {
        mockFace(null, null, true, null);
        String token = register();
        String session = sessionWithConsent(token);
        upload(token, session).andExpect(status().isOk());
        selfie(token, session, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings", hasItem("FACE_NOT_VERIFIED")))
                .andExpect(jsonPath("$.warnings", not(hasItem("FACE_MISMATCH"))));
    }

    @Test
    void retakingTheFrontClearsTheFaceCheck() throws Exception {
        mockFace(0.72, true, true, null);
        String token = register();
        String session = sessionWithConsent(token);
        upload(token, session).andExpect(status().isOk());
        selfie(token, session, 3).andExpect(status().isOk());
        upload(token, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.selfieCaptured").value(false))
                .andExpect(jsonPath("$.faceMatched").doesNotExist());
    }

    @Test
    void selfieIsNotADocumentSide() throws Exception {
        String token = register();
        String session = sessionWithConsent(token);
        mockMvc.perform(multipart("/api/onboarding/sessions/" + session + "/document")
                        .file(new MockMultipartFile("file", "me.jpg", "image/jpeg", PHOTO))
                        .param("documentType", "PASSPORT")
                        .param("side", "SELFIE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
