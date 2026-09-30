package com.onboarding.platform.dto;

import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.enums.DocumentWarning;
import com.onboarding.platform.enums.ReviewStatus;
import com.onboarding.platform.enums.SessionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the review screen renders: the editable extracted fields plus the signals it needs
 * to highlight problems (confidence, checksum result, and the computed warnings).
 */
public record DocumentResponse(
        UUID id,
        UUID sessionId,
        SessionStatus sessionStatus,
        DocumentType documentType,
        Map<String, String> fields,
        /** 0.0-1.0 per key of {@code fields}; a key may be missing if the AI service didn't score it. */
        Map<String, Double> fieldConfidence,
        String documentNumber,
        LocalDate dateOfBirth,
        LocalDate expiryDate,
        Double ocrConfidence,
        Boolean checksumValid,
        boolean userCorrected,
        /** CIN: the back (address, issue date) is a second photo the user still has to take. */
        boolean backSideRequired,
        boolean backSideCaptured,
        /** A selfie is part of the flow; captured once the face check has run. */
        boolean selfieRequired,
        boolean selfieCaptured,
        /** Outcome only -- the applicant never sees the similarity score. Null until checked. */
        Boolean faceMatched,
        Boolean livenessPassed,
        List<DocumentWarning> warnings,
        ReviewStatus reviewStatus,
        /** Why an admin rejected the document; null unless reviewStatus is REJECTED. */
        String decisionReason,
        Instant confirmedAt,
        Instant createdAt
) {}
