package com.onboarding.platform.service;

import com.onboarding.platform.dto.ReviewDecisionRequest;
import com.onboarding.platform.dto.ReviewDetail;
import com.onboarding.platform.dto.ReviewQueueItem;
import com.onboarding.platform.entity.ExtractedDocument;
import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.enums.AuditAction;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.ReviewStatus;
import com.onboarding.platform.enums.SessionStatus;
import com.onboarding.platform.exception.InvalidSessionStateException;
import com.onboarding.platform.exception.ResourceNotFoundException;
import com.onboarding.platform.repository.ExtractedDocumentRepository;
import com.onboarding.platform.repository.OnboardingSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The manual review queue: documents the applicant confirmed but that couldn't be
 * auto-approved (any DocumentWarning). Every look at personal data is audited.
 */
@Service
@RequiredArgsConstructor
public class AdminReviewService {

    private final ExtractedDocumentRepository documentRepository;
    private final OnboardingSessionRepository sessionRepository;
    private final DocumentService documentService;
    private final DocumentImageService imageService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Value("${app.face.match-threshold:0.45}")
    private double faceThreshold;

    @Transactional(readOnly = true)
    public List<ReviewQueueItem> queue(ReviewStatus status) {
        return documentRepository.findByReviewStatusAndConfirmedAtIsNotNullOrderByConfirmedAtAsc(status).stream()
                .map(doc -> new ReviewQueueItem(
                        doc.getId(),
                        doc.getSession().getId(),
                        doc.getSession().getUser().getEmail(),
                        doc.getDocumentType(),
                        doc.getReviewStatus(),
                        documentService.warningsFor(doc),
                        doc.getOcrConfidence(),
                        doc.getConfirmedAt(),
                        doc.getReviewedBy(),
                        doc.getReviewedAt()))
                .toList();
    }

    @Transactional
    public ReviewDetail open(UUID documentId) {
        ExtractedDocument doc = findSubmitted(documentId);
        auditService.record(AuditAction.REVIEW_OPENED, "DOCUMENT", doc.getId(), null);
        return detail(doc);
    }

    @Transactional
    public DocumentImageService.Image image(UUID documentId, DocumentSide side) {
        ExtractedDocument doc = findSubmitted(documentId);
        DocumentImageService.Image image = imageService.load(doc.getId(), side)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No " + side.name().toLowerCase() + " photo stored for this document (photos are deleted once decided)"));
        auditService.record(AuditAction.REVIEW_IMAGE_VIEWED, "DOCUMENT", doc.getId(), side.name());
        return image;
    }

    @Transactional
    public ReviewDetail decide(UUID documentId, ReviewDecisionRequest request) {
        ExtractedDocument doc = findSubmitted(documentId);
        if (doc.getReviewStatus() != ReviewStatus.NEEDS_REVIEW) {
            throw new InvalidSessionStateException("This document has already been decided (" + doc.getReviewStatus() + ")");
        }
        boolean approve = request.decision() == ReviewDecisionRequest.Decision.APPROVE;
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (!approve && reason == null) {
            throw new IllegalArgumentException("A reason is required to reject: the applicant will see it");
        }

        doc.setReviewStatus(approve ? ReviewStatus.APPROVED : ReviewStatus.REJECTED);
        doc.setReviewedBy(SecurityContextHolder.getContext().getAuthentication().getName());
        doc.setReviewedAt(Instant.now());
        doc.setDecisionReason(reason);
        documentRepository.save(doc);

        OnboardingSession session = doc.getSession();
        session.setStatus(approve ? SessionStatus.APPROVED : SessionStatus.REJECTED);
        sessionRepository.save(session);

        // Decided: the photos have served their purpose.
        imageService.deleteAll(doc.getId());
        auditService.record(AuditAction.REVIEW_DECIDED, "DOCUMENT", doc.getId(), approve ? "APPROVED" : "REJECTED");
        notificationService.verificationDecided(session.getUser().getEmail(), approve, reason);
        return detail(doc);
    }

    private ExtractedDocument findSubmitted(UUID documentId) {
        // Documents the applicant hasn't confirmed yet are still theirs to edit: not reviewable.
        return documentRepository.findById(documentId)
                .filter(doc -> doc.getConfirmedAt() != null)
                .orElseThrow(() -> new ResourceNotFoundException("No submitted document " + documentId));
    }

    private ReviewDetail detail(ExtractedDocument doc) {
        return new ReviewDetail(
                doc.getId(),
                doc.getSession().getId(),
                doc.getSession().getUser().getEmail(),
                doc.getDocumentType(),
                doc.getReviewStatus(),
                documentService.fieldsOf(doc),
                documentService.originalFieldsOf(doc),
                documentService.fieldConfidenceOf(doc),
                doc.getOcrConfidence(),
                doc.getChecksumValid(),
                doc.getExpiryDate(),
                documentService.warningsFor(doc),
                doc.getFaceSimilarity(),
                faceThreshold,
                doc.getFaceMatched(),
                doc.getLivenessPassed(),
                doc.getLivenessReason(),
                imageService.availableSides(doc.getId()),
                doc.getConfirmedAt(),
                doc.getReviewedBy(),
                doc.getReviewedAt(),
                doc.getDecisionReason());
    }
}
