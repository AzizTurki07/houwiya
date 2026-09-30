package com.onboarding.platform.dto;

import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.enums.DocumentWarning;
import com.onboarding.platform.enums.ReviewStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything a reviewer needs to decide on one document. */
public record ReviewDetail(
        UUID documentId,
        UUID sessionId,
        String applicantEmail,
        DocumentType documentType,
        ReviewStatus reviewStatus,
        /** Values as confirmed by the applicant. */
        Map<String, String> fields,
        /** Values as the OCR read them -- only present if the applicant changed something. */
        Map<String, String> originalFields,
        Map<String, Double> fieldConfidence,
        Double ocrConfidence,
        Boolean checksumValid,
        LocalDate expiryDate,
        List<DocumentWarning> warnings,
        /** Which photos are still stored (they are deleted once a decision is made). */
        List<DocumentSide> imageSides,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String decisionReason
) {}
