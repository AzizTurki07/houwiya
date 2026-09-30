package com.onboarding.platform.repository;

import com.onboarding.platform.entity.ExtractedDocument;
import com.onboarding.platform.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExtractedDocumentRepository extends JpaRepository<ExtractedDocument, UUID> {

    // Duplicate-applicant check: the number itself is encrypted, so match on its keyed hash.
    List<ExtractedDocument> findByDocumentNumberHash(String documentNumberHash);

    // Admin queue: documents the applicant has submitted, oldest first.
    List<ExtractedDocument> findByReviewStatusAndConfirmedAtIsNotNullOrderByConfirmedAtAsc(ReviewStatus reviewStatus);

    Optional<ExtractedDocument> findBySessionId(UUID sessionId);
}
