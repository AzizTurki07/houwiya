package com.onboarding.platform.controller;

import com.onboarding.platform.dto.SessionResponse;
import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.entity.User;
import com.onboarding.platform.security.CurrentUserProvider;
import com.onboarding.platform.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 1 scope: session lifecycle + consent only.
 * Document upload -> AI extraction wiring lands in Phase 2/3 of the roadmap
 * (extract to Python service, persist ExtractedDocument, update session status).
 */
@RestController
@RequestMapping("/api/onboarding/sessions")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ResponseEntity<SessionResponse> createSession() {
        User user = currentUserProvider.getCurrentUser();
        OnboardingSession session = onboardingService.createSession(user);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    @PostMapping("/{id}/consent")
    public ResponseEntity<SessionResponse> giveConsent(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        OnboardingSession session = onboardingService.giveConsent(id, user);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        OnboardingSession session = onboardingService.getOwnedSession(id, user);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> listMySessions() {
        User user = currentUserProvider.getCurrentUser();
        List<SessionResponse> sessions = onboardingService.listForUser(user)
                .stream().map(SessionResponse::from).toList();
        return ResponseEntity.ok(sessions);
    }
}
