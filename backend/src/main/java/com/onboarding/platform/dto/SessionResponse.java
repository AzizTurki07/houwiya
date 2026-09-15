package com.onboarding.platform.dto;

import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.enums.SessionStatus;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        SessionStatus status,
        boolean consentGiven,
        Instant createdAt,
        Instant updatedAt
) {
    public static SessionResponse from(OnboardingSession session) {
        return new SessionResponse(
                session.getId(),
                session.getStatus(),
                session.isConsentGiven(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
