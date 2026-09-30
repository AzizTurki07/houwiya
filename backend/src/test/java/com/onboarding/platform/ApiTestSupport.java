package com.onboarding.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onboarding.platform.client.AiServiceClient;
import com.onboarding.platform.enums.DocumentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers for API-level tests: H2 + Flyway, the Python AI service mocked out. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {

    protected static final byte[] PHOTO = "fake-jpeg-bytes-for-tests".getBytes();
    // Created at startup by AdminBootstrap from application-test.yml.
    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_PASSWORD = "admin-test-password";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockBean
    protected AiServiceClient aiServiceClient;

    protected void mockPassport(String number, String givenNames, double confidence, boolean checksumValid, String expiry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_number", number);
        result.put("surname", "BENALI");
        result.put("given_names", givenNames);
        result.put("nationality", "TUN");
        result.put("date_of_birth", "1995-03-11");
        result.put("sex", "M");
        result.put("expiry_date", expiry);
        result.put("checksum_valid", checksumValid);
        result.put("overall_confidence", confidence);
        result.put("field_confidence", Map.of("document_number", confidence, "surname", confidence,
                "given_names", confidence, "nationality", confidence, "date_of_birth", confidence,
                "sex", confidence, "expiry_date", confidence));
        JsonNode node = objectMapper.valueToTree(result);
        when(aiServiceClient.extract(eq(DocumentType.PASSPORT), any(), any(), any(), any())).thenReturn(node);
    }

    protected String register() throws Exception {
        return token(post("/api/auth/register"), "user-" + UUID.randomUUID() + "@example.com", "password123");
    }

    protected String loginAdmin() throws Exception {
        return token(post("/api/auth/login"), ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String token(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                         String email, String password) throws Exception {
        String body = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    protected String sessionWithConsent(String token) throws Exception {
        String body = mockMvc.perform(post("/api/onboarding/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        mockMvc.perform(post("/api/onboarding/sessions/" + id + "/consent").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    protected ResultActions upload(String token, String sessionId) throws Exception {
        return mockMvc.perform(multipart("/api/onboarding/sessions/" + sessionId + "/document")
                .file(new MockMultipartFile("file", "passport.jpg", "image/jpeg", PHOTO))
                .param("documentType", "PASSPORT")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions confirm(String token, String sessionId, Map<String, String> fields) throws Exception {
        return mockMvc.perform(post("/api/onboarding/sessions/" + sessionId + "/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("fields", fields))));
    }

    /** Uploads + confirms; returns the document id. */
    protected String submit(String token, String sessionId, Map<String, String> fields) throws Exception {
        upload(token, sessionId).andExpect(status().isOk());
        String body = confirm(token, sessionId, fields).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
