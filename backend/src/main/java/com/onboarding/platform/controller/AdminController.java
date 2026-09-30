package com.onboarding.platform.controller;

import com.onboarding.platform.dto.AuditEventResponse;
import com.onboarding.platform.dto.ReviewDecisionRequest;
import com.onboarding.platform.dto.ReviewDetail;
import com.onboarding.platform.dto.ReviewQueueItem;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.ReviewStatus;
import com.onboarding.platform.service.AdminReviewService;
import com.onboarding.platform.service.AuditService;
import com.onboarding.platform.service.DocumentImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Manual review queue and audit trail. Everything under /api/admin requires ROLE_ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminReviewService reviewService;
    private final AuditService auditService;

    /** Submitted documents with the given status, oldest first. Default: the ones waiting on a decision. */
    @GetMapping("/reviews")
    public List<ReviewQueueItem> queue(@RequestParam(defaultValue = "NEEDS_REVIEW") ReviewStatus status) {
        return reviewService.queue(status);
    }

    @GetMapping("/reviews/{documentId}")
    public ReviewDetail open(@PathVariable UUID documentId) {
        return reviewService.open(documentId);
    }

    /** A document photo, decrypted on the fly. Never cached: it is personal data. */
    @GetMapping("/reviews/{documentId}/images/{side}")
    public ResponseEntity<byte[]> image(@PathVariable UUID documentId, @PathVariable DocumentSide side) {
        DocumentImageService.Image image = reviewService.image(documentId, side);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(image.bytes());
    }

    @PostMapping("/reviews/{documentId}/decision")
    public ReviewDetail decide(@PathVariable UUID documentId, @Valid @RequestBody ReviewDecisionRequest request) {
        return reviewService.decide(documentId, request);
    }

    /** The audit trail: latest events, or every event about one session/document. */
    @GetMapping("/audit")
    public List<AuditEventResponse> audit(@RequestParam(required = false) UUID targetId,
                                          @RequestParam(defaultValue = "100") int limit) {
        return (targetId != null ? auditService.forTarget(targetId) : auditService.latest(limit))
                .stream().map(AuditEventResponse::from).toList();
    }
}
