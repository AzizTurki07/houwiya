package com.onboarding.platform.controller;

import com.onboarding.platform.ApiTestSupport;
import com.onboarding.platform.service.DocumentImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Encryption at rest, photo retention, erasure and the audit trail, checked at the database level. */
class DataProtectionTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DocumentImageService imageService;

    @Test
    void personalData_isEncryptedAtRest() throws Exception {
        String user = register();
        mockPassport("E2000001", "SAMI", 0.4, true, "2031-05-01");
        String documentId = submit(user, sessionWithConsent(user), Map.of());

        Map<String, Object> row = jdbc.queryForMap(
                "select document_number, document_number_hash, date_of_birth, extracted_fields_json "
                        + "from extracted_document where id = ?", UUID.fromString(documentId));
        String everything = row.values().toString();
        assertThat(everything).doesNotContain("E2000001", "BENALI", "SAMI", "1995-03-11");
        assertThat((String) row.get("document_number")).startsWith("v1:");
        assertThat((String) row.get("date_of_birth")).startsWith("v1:");
        assertThat((String) row.get("extracted_fields_json")).startsWith("v1:");
        assertThat((String) row.get("document_number_hash")).hasSize(64);

        byte[] stored = jdbc.queryForObject("select data from document_image where document_id = ?",
                byte[].class, UUID.fromString(documentId));
        assertThat(new String(stored)).doesNotContain(new String(PHOTO));

        // ...while the API still returns the real values to their owner.
        mockMvc.perform(get("/api/admin/reviews/" + documentId).header("Authorization", "Bearer " + loginAdmin()))
                .andExpect(jsonPath("$.fields.document_number").value("E2000001"));
    }

    @Test
    void autoApprovedDocuments_keepNoPhotos() throws Exception {
        String user = register();
        mockPassport("E2000002", "SAMI", 0.95, true, "2031-05-01");
        String documentId = submit(user, sessionWithConsent(user), Map.of());

        assertThat(imagesOf(documentId)).isZero();
    }

    @Test
    void photosOlderThanTheRetentionPeriod_arePurged() throws Exception {
        String user = register();
        mockPassport("E2000003", "SAMI", 0.4, true, "2031-05-01");
        String documentId = submit(user, sessionWithConsent(user), Map.of());
        assertThat(imagesOf(documentId)).isOne();

        jdbc.update("update document_image set created_at = created_at - interval '40' day where document_id = ?",
                UUID.fromString(documentId));
        imageService.purgeExpired();

        assertThat(imagesOf(documentId)).isZero();
    }

    @Test
    void deletingASession_erasesTheDocumentAndPhotos_andIsAudited() throws Exception {
        String user = register();
        mockPassport("E2000004", "SAMI", 0.4, true, "2031-05-01");
        String sessionId = sessionWithConsent(user);
        String documentId = submit(user, sessionId, Map.of());

        mockMvc.perform(delete("/api/onboarding/sessions/" + sessionId).header("Authorization", "Bearer " + user))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/onboarding/sessions/" + sessionId).header("Authorization", "Bearer " + user))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from extracted_document where id = ?", Integer.class,
                UUID.fromString(documentId))).isZero();
        assertThat(imagesOf(documentId)).isZero();
        mockMvc.perform(get("/api/admin/audit").param("targetId", sessionId).header("Authorization", "Bearer " + loginAdmin()))
                .andExpect(jsonPath("$[*].action", hasItem("SESSION_DELETED")));
    }

    @Test
    void someoneElse_cannotDeleteYourSession() throws Exception {
        String owner = register();
        String sessionId = sessionWithConsent(owner);
        mockMvc.perform(delete("/api/onboarding/sessions/" + sessionId).header("Authorization", "Bearer " + register()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAuditTrail_recordsActionsButNeverDocumentValues() throws Exception {
        String user = register();
        mockPassport("E2000005", "SAMI", 0.4, true, "2031-05-01");
        String sessionId = sessionWithConsent(user);
        String documentId = submit(user, sessionId, Map.of("given_names", "SAMY"));

        List<Map<String, Object>> events = jdbc.queryForList(
                "select action, details from audit_event where target_id in (?, ?)",
                UUID.fromString(sessionId), UUID.fromString(documentId));
        assertThat(events).extracting(e -> e.get("action"))
                .contains("SESSION_CREATED", "CONSENT_GIVEN", "DOCUMENT_UPLOADED", "DOCUMENT_CONFIRMED");
        assertThat(events.toString()).doesNotContain("E2000005", "BENALI", "SAMI", "SAMY");
    }

    @Test
    void anInvalidToken_isA401_notAServerError() throws Exception {
        mockMvc.perform(get("/api/onboarding/sessions").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private int imagesOf(String documentId) {
        return jdbc.queryForObject("select count(*) from document_image where document_id = ?", Integer.class,
                UUID.fromString(documentId));
    }
}
