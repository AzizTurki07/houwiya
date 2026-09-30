package com.onboarding.platform.dto;

import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.enums.DocumentWarning;
import com.onboarding.platform.enums.ReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of the admin review queue. Deliberately no document values: those are only
 *  decrypted (and audited) when an admin opens the document. */
public record ReviewQueueItem(
        UUID documentId,
        UUID sessionId,
        String applicantEmail,
        DocumentType documentType,
        ReviewStatus reviewStatus,
        List<DocumentWarning> warnings,
        Double ocrConfidence,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt
) {}
