package com.onboarding.platform.repository;

import com.onboarding.platform.entity.ExtractedDocument;
import com.onboarding.platform.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExtractedDocumentRepository extends JpaRepository<ExtractedDocument, UUID> {

    // Backs the duplicate-applicant check in Phase 5 (week 5 of the roadmap).
    Optional<ExtractedDocument> findByDocumentNumber(String documentNumber);

    // Backs the admin review queue in Phase 5 (week 6).
    List<ExtractedDocument> findByReviewStatus(ReviewStatus reviewStatus);

    Optional<ExtractedDocument> findBySessionId(UUID sessionId);
}
