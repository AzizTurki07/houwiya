package com.onboarding.platform.dto;

import com.onboarding.platform.entity.AuditEvent;
import com.onboarding.platform.enums.AuditAction;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        Instant occurredAt,
        String actor,
        AuditAction action,
        String targetType,
        UUID targetId,
        String details
) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActor(), e.getAction(),
                e.getTargetType(), e.getTargetId(), e.getDetails());
    }
}
