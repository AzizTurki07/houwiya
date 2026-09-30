package com.onboarding.platform.controller;

import com.onboarding.platform.dto.ConfirmDocumentRequest;
import com.onboarding.platform.dto.DocumentResponse;
import com.onboarding.platform.dto.SessionResponse;
import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.entity.User;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.security.CurrentUserProvider;
import com.onboarding.platform.service.DocumentService;
import com.onboarding.platform.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Onboarding flow: create session -> consent -> upload document (AI extraction)
 * -> review extracted fields -> confirm.
 */
@RestController
@RequestMapping("/api/onboarding/sessions")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final DocumentService documentService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ResponseEntity<SessionResponse> createSession() {
        User user = currentUserProvider.getCurrentUser();
        OnboardingSession session = onboardingService.createSession(user);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    @PostMapping("/{id}/consent")
    public ResponseEntity<SessionResponse> giveConsent(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        OnboardingSession session = onboardingService.giveConsent(id, user);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        OnboardingSession session = onboardingService.getOwnedSession(id, user);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    /** Deletes the session with its document and photos (the applicant's right to erasure). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        documentService.deleteSession(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> listMySessions() {
        User user = currentUserProvider.getCurrentUser();
        List<SessionResponse> sessions = onboardingService.listForUser(user)
                .stream().map(SessionResponse::from).toList();
        return ResponseEntity.ok(sessions);
    }

    /**
     * Sends the photo to the AI service and stores the extracted fields for review.
     * 422 = unreadable image (prompt a retake); calling this again before confirming replaces
     * that side. A CIN needs a second call with side=BACK (address, issue date).
     */
    @PostMapping(value = "/{id}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(@PathVariable UUID id,
                                                           @RequestParam("documentType") DocumentType documentType,
                                                           @RequestParam(value = "side", defaultValue = "FRONT") DocumentSide side,
                                                           @RequestParam("file") MultipartFile file) {
        User user = currentUserProvider.getCurrentUser();
        return ResponseEntity.ok(documentService.uploadDocument(id, user, documentType, side, file));
    }

    /** Three selfie frames (straight, turned one way, turned the other) for the face match. */
    @PostMapping(value = "/{id}/selfie", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> submitSelfie(@PathVariable UUID id,
                                                         @RequestParam("frames") List<MultipartFile> frames) {
        User user = currentUserProvider.getCurrentUser();
        return ResponseEntity.ok(documentService.submitSelfie(id, user, frames));
    }

    @GetMapping("/{id}/document")
    public ResponseEntity<DocumentResponse> getDocument(@PathVariable UUID id) {
        User user = currentUserProvider.getCurrentUser();
        return ResponseEntity.ok(documentService.getDocument(id, user));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<DocumentResponse> confirmDocument(@PathVariable UUID id,
                                                            @Valid @RequestBody ConfirmDocumentRequest request) {
        User user = currentUserProvider.getCurrentUser();
        return ResponseEntity.ok(documentService.confirmDocument(id, user, request.fields()));
    }
}
