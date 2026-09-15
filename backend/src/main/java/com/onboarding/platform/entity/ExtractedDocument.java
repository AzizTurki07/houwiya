package com.onboarding.platform.entity;

import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.enums.ReviewStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Stores the result of running a captured document image through the AI service.
 * Note: rawImagePath is intentionally nullable/optional -- decide during Phase 7
 * (hardening) whether you keep the original image at all, per your data-retention policy.
 */
@Entity
@Table(name = "extracted_document")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private OnboardingSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    // Used for duplicate-applicant detection (see OnboardingService).
    @Column(unique = true)
    private String documentNumber;

    private LocalDate dateOfBirth;
    private LocalDate expiryDate;

    // Raw JSON blob of all fields the AI service returned (name, place of birth, etc).
    // Keeping this as text instead of a rigid column-per-field schema, since CIN and
    // passport extract different field sets.
    @Lob
    private String extractedFieldsJson;

    private Double ocrConfidence;          // 0.0 - 1.0, lowest per-field confidence
    private Boolean checksumValid;         // MRZ check-digit result; null for CIN (no MRZ)

    private String rawImagePath;           // nullable -- see retention note above

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
