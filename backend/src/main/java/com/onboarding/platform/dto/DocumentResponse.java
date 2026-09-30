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
        String documentNumber,
        LocalDate dateOfBirth,
        LocalDate expiryDate,
        Double ocrConfidence,
        Boolean checksumValid,
        boolean userCorrected,
        List<DocumentWarning> warnings,
        ReviewStatus reviewStatus,
        Instant confirmedAt,
        Instant createdAt
) {}
