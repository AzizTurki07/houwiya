package com.onboarding.platform.controller;

import com.onboarding.platform.ApiTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Which browser origins may call the API (application.yml app.cors.allowed-origin-patterns). */
class CorsTest extends ApiTestSupport {

    private static final String BAD_LOGIN = "{\"email\":\"nobody@example.test\",\"password\":\"wrong-password\"}";

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:4200",
            // A Codespace port forwarded to your own machine (e.g. VS Code desktop).
            "https://localhost:4200",
            "http://127.0.0.1:8080",
            "https://my-codespace-4200.app.github.dev",
            // The Android (Capacitor) app's WebView.
            "https://localhost"
    })
    void allowedOriginsReachTheApi(String origin) throws Exception {
        // 401 = the request got through CORS to the login check (a rejected origin is 403).
        mockMvc.perform(post("/api/auth/login").header("Origin", origin)
                        .contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://localhost.evil.example:4200", "https://app.github.dev.evil.example"})
    void otherOriginsAreRefused(String origin) throws Exception {
        mockMvc.perform(post("/api/auth/login").header("Origin", origin)
                        .contentType(MediaType.APPLICATION_JSON).content(BAD_LOGIN))
                .andExpect(status().isForbidden());
    }
}
