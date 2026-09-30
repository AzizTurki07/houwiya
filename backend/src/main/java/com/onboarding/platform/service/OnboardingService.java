package com.onboarding.platform.service;

import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.entity.User;
import com.onboarding.platform.enums.AuditAction;
import com.onboarding.platform.enums.SessionStatus;
import com.onboarding.platform.exception.ResourceNotFoundException;
import com.onboarding.platform.repository.OnboardingSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final OnboardingSessionRepository sessionRepository;
    private final AuditService auditService;

    public OnboardingSession createSession(User user) {
        OnboardingSession session = OnboardingSession.builder()
                .user(user)
                .status(SessionStatus.STARTED)
                .consentGiven(false)
                .build();
        session = sessionRepository.save(session);
        auditService.record(AuditAction.SESSION_CREATED, "SESSION", session.getId(), null);
        return session;
    }

    public OnboardingSession giveConsent(UUID sessionId, User user) {
        OnboardingSession session = getOwnedSession(sessionId, user);
        // Idempotent: re-consenting must not rewind a session that has already moved on.
        if (session.isConsentGiven()) {
            return session;
        }
        session.setConsentGiven(true);
        session.setConsentTimestamp(Instant.now());
        session.setStatus(SessionStatus.CONSENT_GIVEN);
        auditService.record(AuditAction.CONSENT_GIVEN, "SESSION", session.getId(), null);
        return sessionRepository.save(session);
    }

    public OnboardingSession getOwnedSession(UUID sessionId, User user) {
        // Same 404 for "doesn't exist" and "not yours", so other users' session IDs can't be probed.
        return sessionRepository.findById(sessionId)
                .filter(session -> session.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Session not found: " + sessionId));
    }

    public List<OnboardingSession> listForUser(User user) {
        return sessionRepository.findByUser(user);
    }
}
