package com.onboarding.platform.service;

import com.onboarding.platform.crypto.FieldEncryptor;
import com.onboarding.platform.entity.DocumentImage;
import com.onboarding.platform.enums.AuditAction;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.repository.DocumentImageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Document photos, and the retention policy around them.
 *
 * Photos are stored only so a reviewer can compare a flagged document with what the user
 * submitted. They are encrypted at rest and deleted as soon as they stop being needed:
 *   - when the document is auto-approved at confirmation,
 *   - when an admin approves or rejects it,
 *   - when the user deletes the session (database cascade),
 *   - and, whatever happens, after app.retention.image-days (daily clean-up).
 */
@Slf4j
@Service
public class DocumentImageService {

    public record Image(String contentType, byte[] bytes) {}

    private final DocumentImageRepository repository;
    private final FieldEncryptor encryptor;
    private final AuditService auditService;
    private final Duration maxAge;

    public DocumentImageService(DocumentImageRepository repository, FieldEncryptor encryptor, AuditService auditService,
                                @Value("${app.retention.image-days:30}") long imageDays) {
        this.repository = repository;
        this.encryptor = encryptor;
        this.auditService = auditService;
        this.maxAge = Duration.ofDays(imageDays);
    }

    /** Stores (or replaces) the photo of one side of a document. */
    @Transactional
    public void store(UUID documentId, DocumentSide side, String contentType, byte[] bytes) {
        repository.deleteByDocumentIdAndSide(documentId, side);
        repository.flush();
        repository.save(DocumentImage.builder()
                .documentId(documentId)
                .side(side)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .data(encryptor.encryptBytes(bytes))
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<Image> load(UUID documentId, DocumentSide side) {
        return repository.findByDocumentIdAndSide(documentId, side)
                .map(img -> new Image(img.getContentType(), encryptor.decryptBytes(img.getData())));
    }

    @Transactional(readOnly = true)
    public List<DocumentSide> availableSides(UUID documentId) {
        return repository.findSidesByDocumentId(documentId);
    }

    @Transactional
    public void deleteSide(UUID documentId, DocumentSide side) {
        repository.deleteByDocumentIdAndSide(documentId, side);
    }

    /** Called once a document no longer needs a human look (auto-approved or decided). */
    @Transactional
    public void deleteAll(UUID documentId) {
        repository.deleteByDocumentId(documentId);
    }

    /** Daily: photos past the retention period go, reviewed or not. */
    @Scheduled(cron = "${app.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public int purgeExpired() {
        int purged = repository.deleteOlderThan(Instant.now().minus(maxAge));
        if (purged > 0) {
            log.info("Retention clean-up deleted {} document photo(s) older than {} days", purged, maxAge.toDays());
            auditService.recordAs("system", AuditAction.IMAGES_PURGED, null, null,
                    purged + " photo(s) older than " + maxAge.toDays() + " days");
        }
        return purged;
    }
}
