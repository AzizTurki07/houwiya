package com.onboarding.platform.entity;

import com.onboarding.platform.enums.AuditAction;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of who did what, when. Holds ids and short non-personal details only --
 * never extracted document values -- so the log itself doesn't become a copy of the data.
 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    private String actor;                  // e-mail of the signed-in user, or the attempted one

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    @Column(length = 50)
    private String targetType;             // "SESSION", "DOCUMENT", "USER"

    private UUID targetId;

    @Column(length = 500)
    private String details;

    @PrePersist
    void onCreate() {
        occurredAt = Instant.now();
    }
}
