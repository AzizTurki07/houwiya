package com.onboarding.platform.entity;

import com.onboarding.platform.crypto.EncryptedLocalDateConverter;
import com.onboarding.platform.crypto.EncryptedStringConverter;
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
 * The result of running a captured document through the AI service, plus its review outcome.
 * Columns holding personal data are encrypted at rest (see FieldEncryptor); the photos
 * themselves live in DocumentImage, and only until a decision is made.
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

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String documentNumber;

    // Keyed hash of documentNumber: lets the duplicate-applicant check find other applications
    // with the same number without decrypting (or storing in clear) any number.
    @Column(length = 64)
    private String documentNumberHash;

    @Convert(converter = EncryptedLocalDateConverter.class)
    @Column(columnDefinition = "TEXT")
    private LocalDate dateOfBirth;

    private LocalDate expiryDate;

    // All fields the AI service returned (names, address, per-field confidence...), as JSON
    // text: CIN and passport extract different field sets. Encrypted.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String extractedFieldsJson;

    // What the OCR read, kept when the user corrects anything, so a reviewer can compare.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String originalFieldsJson;

    private Double ocrConfidence;          // 0.0 - 1.0, lowest per-field confidence
    private Boolean checksumValid;         // MRZ check-digit result; null for CIN (no MRZ)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    // True once the user edits any extracted value on the review screen. Edited documents
    // are never auto-approved -- an admin compares them against the photo.
    @Column(nullable = false)
    @Builder.Default
    private boolean userCorrected = false;

    private Instant confirmedAt;           // set when the user confirms the reviewed fields

    // CIN only: the address/profession/issue date are on the back, a second photo.
    @Column(nullable = false)
    @Builder.Default
    private boolean backSideCaptured = false;

    // Manual review outcome (admin queue).
    private String reviewedBy;             // admin e-mail
    private Instant reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String decisionReason;         // shown to the applicant when rejected

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
