package com.onboarding.platform.service;

import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.entity.User;
import com.onboarding.platform.enums.SessionStatus;
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

    public OnboardingSession createSession(User user) {
        OnboardingSession session = OnboardingSession.builder()
                .user(user)
                .status(SessionStatus.STARTED)
                .consentGiven(false)
                .build();
        return sessionRepository.save(session);
    }

    public OnboardingSession giveConsent(UUID sessionId, User user) {
        OnboardingSession session = getOwnedSession(sessionId, user);
        session.setConsentGiven(true);
        session.setConsentTimestamp(Instant.now());
        session.setStatus(SessionStatus.CONSENT_GIVEN);
        return sessionRepository.save(session);
    }

    public OnboardingSession getOwnedSession(UUID sessionId, User user) {
        OnboardingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        if (!session.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Session does not belong to this user");
        }
        return session;
    }

    public List<OnboardingSession> listForUser(User user) {
        return sessionRepository.findByUser(user);
    }
}
