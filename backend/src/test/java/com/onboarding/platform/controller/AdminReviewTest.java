package com.onboarding.platform.controller;

import com.onboarding.platform.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminReviewTest extends ApiTestSupport {

    @Test
    void adminEndpoints_needAnAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/reviews")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/reviews").header("Authorization", "Bearer " + register()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reviews").header("Authorization", "Bearer " + loginAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void queue_holdsOnlySubmittedFlaggedDocuments_withoutTheirValues() throws Exception {
        String user = register();
        mockPassport("Q1000001", "SAMI", 0.4, true, "2031-05-01");

        String draft = sessionWithConsent(user);
        upload(user, draft); // uploaded but not confirmed: still the applicant's, not in the queue

        String submitted = sessionWithConsent(user);
        mockPassport("Q1000002", "SAMI", 0.4, true, "2031-05-01");
        String documentId = submit(user, submitted, Map.of());

        String admin = loginAdmin();
        mockMvc.perform(get("/api/admin/reviews").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].documentId", hasItem(documentId)))
                .andExpect(jsonPath("$[*].sessionId", not(hasItem(draft))))
                .andExpect(jsonPath("$[?(@.documentId == '" + documentId + "')].warnings[0]").value("LOW_CONFIDENCE"))
                // The list shows who and why, never the document's contents.
                .andExpect(content().string(not(containsString("Q1000002"))))
                .andExpect(content().string(not(containsString("BENALI"))));
    }

    @Test
    void reviewer_seesWhatTheUserChanged_andThePhoto() throws Exception {
        String user = register();
        mockPassport("Q1000003", "SAMI", 0.95, true, "2031-05-01");
        String documentId = submit(user, sessionWithConsent(user), Map.of("given_names", "SAMY"));

        String admin = loginAdmin();
        mockMvc.perform(get("/api/admin/reviews/" + documentId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.given_names").value("SAMY"))
                .andExpect(jsonPath("$.originalFields.given_names").value("SAMI"))
                .andExpect(jsonPath("$.warnings", hasItem("USER_CORRECTED")))
                .andExpect(jsonPath("$.imageSides", hasItem("FRONT")));

        mockMvc.perform(get("/api/admin/reviews/" + documentId + "/images/FRONT").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(PHOTO))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        // Both looks at personal data are in the audit trail.
        mockMvc.perform(get("/api/admin/audit").param("targetId", documentId).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[*].action", hasItem("REVIEW_OPENED")))
                .andExpect(jsonPath("$[*].action", hasItem("REVIEW_IMAGE_VIEWED")));
    }

    @Test
    void approving_verifiesTheApplicant_andDeletesThePhotos() throws Exception {
        String user = register();
        mockPassport("Q1000004", "SAMI", 0.4, true, "2031-05-01");
        String sessionId = sessionWithConsent(user);
        String documentId = submit(user, sessionId, Map.of());
        String admin = loginAdmin();

        decide(admin, documentId, "APPROVE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.imageSides").isEmpty());

        mockMvc.perform(get("/api/onboarding/sessions/" + sessionId).header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/api/admin/reviews/" + documentId + "/images/FRONT").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        // A decision is final.
        decide(admin, documentId, "REJECT", "changed my mind").andExpect(status().isConflict());
    }

    @Test
    void rejecting_needsAReason_thatTheApplicantSees() throws Exception {
        String user = register();
        mockPassport("Q1000005", "SAMI", 0.4, true, "2031-05-01");
        String sessionId = sessionWithConsent(user);
        String documentId = submit(user, sessionId, Map.of());
        String admin = loginAdmin();

        decide(admin, documentId, "REJECT", "  ").andExpect(status().isBadRequest());
        decide(admin, documentId, "REJECT", "The photo is too blurry to read the name.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));

        mockMvc.perform(get("/api/onboarding/sessions/" + sessionId + "/document").header("Authorization", "Bearer " + user))
                .andExpect(jsonPath("$.sessionStatus").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("The photo is too blurry to read the name."));
    }

    @Test
    void afterARejection_theSamePersonMayReapply_butNobodyElse() throws Exception {
        String user = register();
        mockPassport("Q1000006", "SAMI", 0.4, true, "2031-05-01");
        String documentId = submit(user, sessionWithConsent(user), Map.of());

        // While the first application is pending, the same document can't be used again.
        upload(user, sessionWithConsent(user)).andExpect(status().isConflict());

        decide(loginAdmin(), documentId, "REJECT", "Unreadable").andExpect(status().isOk());
        upload(user, sessionWithConsent(user)).andExpect(status().isOk());

        String someoneElse = register();
        upload(someoneElse, sessionWithConsent(someoneElse)).andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.ResultActions decide(String admin, String documentId, String decision,
                                                                      String reason) throws Exception {
        String body = reason == null
                ? objectMapper.writeValueAsString(Map.of("decision", decision))
                : objectMapper.writeValueAsString(Map.of("decision", decision, "reason", reason));
        return mockMvc.perform(post("/api/admin/reviews/" + documentId + "/decision")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
