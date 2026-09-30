package com.onboarding.platform.controller;

import com.onboarding.platform.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.rate-limit.auth-per-minute=5",
        "app.rate-limit.uploads-per-hour=2"
})
class RateLimitTest extends ApiTestSupport {

    @Test
    void repeatedLogins_areThrottled() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", "nobody@example.com", "password", "wrong-password"));
        int throttled = 0;
        for (int i = 0; i < 8; i++) {
            int status = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                            .content(body))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                throttled++;
            }
        }
        org.assertj.core.api.Assertions.assertThat(throttled).isEqualTo(3);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().string(containsString("Too many attempts")));

        // Another client is unaffected.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr("203.0.113.8"); return r; })
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploads_areThrottledPerUser() throws Exception {
        String user = register();
        mockPassport("R3000001", "SAMI", 0.95, true, "2031-05-01");
        String sessionId = sessionWithConsent(user);

        upload(user, sessionId).andExpect(status().isOk());
        upload(user, sessionId).andExpect(status().isOk()); // retake
        upload(user, sessionId).andExpect(status().isTooManyRequests());

        String other = register();
        mockPassport("R3000002", "SAMI", 0.95, true, "2031-05-01");
        upload(other, sessionWithConsent(other)).andExpect(status().isOk());
    }
}
