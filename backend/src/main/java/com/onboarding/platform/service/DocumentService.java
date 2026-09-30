package com.onboarding.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onboarding.platform.client.AiServiceClient;
import com.onboarding.platform.dto.DocumentResponse;
import com.onboarding.platform.entity.ExtractedDocument;
import com.onboarding.platform.entity.OnboardingSession;
import com.onboarding.platform.entity.User;
import com.onboarding.platform.enums.DocumentSide;
import com.onboarding.platform.enums.DocumentType;
import com.onboarding.platform.enums.DocumentWarning;
import com.onboarding.platform.enums.ReviewStatus;
import com.onboarding.platform.enums.SessionStatus;
import com.onboarding.platform.exception.DuplicateDocumentException;
import com.onboarding.platform.exception.InvalidSessionStateException;
import com.onboarding.platform.exception.ResourceNotFoundException;
import com.onboarding.platform.repository.ExtractedDocumentRepository;
import com.onboarding.platform.repository.OnboardingSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Upload -> AI extraction -> persist -> user review/confirm.
 *
 * Status flow: CONSENT_GIVEN --upload--> PENDING_REVIEW --confirm--> APPROVED (clean document)
 * or stays PENDING_REVIEW with ReviewStatus.NEEDS_REVIEW (any warning -> admin queue).
 * Until the user confirms, re-uploading replaces the document (the "retake" path).
 *
 * A CIN is two photos: the front creates the document, the back (address, profession, issue
 * date) is merged into it, and the document can't be confirmed until both are in.
 */
@Service
public class DocumentService {

    // Keys in the AI service's JSON that are metadata, not user-editable document fields.
    private static final String FIELD_CONFIDENCE_KEY = "field_confidence";
    private static final String OVERALL_CONFIDENCE_KEY = "overall_confidence";
    private static final Set<String> NON_FIELD_KEYS = Set.of(OVERALL_CONFIDENCE_KEY, "checksum_valid", FIELD_CONFIDENCE_KEY);

    private final OnboardingService onboardingService;
    private final OnboardingSessionRepository sessionRepository;
    private final ExtractedDocumentRepository documentRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final double minConfidence;

    public DocumentService(OnboardingService onboardingService,
                           OnboardingSessionRepository sessionRepository,
                           ExtractedDocumentRepository documentRepository,
                           AiServiceClient aiServiceClient,
                           ObjectMapper objectMapper,
                           TransactionTemplate transactionTemplate,
                           @Value("${app.review.min-confidence:0.7}") double minConfidence) {
        this.onboardingService = onboardingService;
        this.sessionRepository = sessionRepository;
        this.documentRepository = documentRepository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.minConfidence = minConfidence;
    }

    public DocumentResponse uploadDocument(UUID sessionId, User user, DocumentType type, DocumentSide side,
                                           MultipartFile file) {
        OnboardingSession session = onboardingService.getOwnedSession(sessionId, user);
        requireUploadAllowed(session);
        validateImage(file);
        if (side == DocumentSide.BACK) {
            requireFrontForBack(session, type);
        }

        // Deliberately outside any transaction: OCR can take seconds and shouldn't hold a DB connection.
        JsonNode extraction = aiServiceClient.extract(type, side, readBytes(file), file.getOriginalFilename(), file.getContentType());
        if (extraction == null || !extraction.isObject()) {
            throw new IllegalStateException("AI service returned an empty extraction result");
        }

        return transactionTemplate.execute(status -> {
            ExtractedDocument document = side == DocumentSide.BACK
                    ? mergeBackSide(findDocument(session), (ObjectNode) extraction)
                    : replaceFrontSide(session, type, (ObjectNode) extraction);
            requireNotDuplicate(document);
            document.setReviewStatus(computeWarnings(document).isEmpty() ? ReviewStatus.PENDING : ReviewStatus.NEEDS_REVIEW);
            documentRepository.save(document);

            session.setStatus(SessionStatus.PENDING_REVIEW);
            return toResponse(document, sessionRepository.save(session));
        });
    }

    /** New document from a front photo. Retaking a CIN front keeps a back that was already captured. */
    private ExtractedDocument replaceFrontSide(OnboardingSession session, DocumentType type, ObjectNode extraction) {
        ObjectNode fields = extraction.deepCopy();
        boolean backCaptured = false;
        Optional<ExtractedDocument> existing = documentRepository.findBySessionId(session.getId());
        if (existing.isPresent()) {
            ExtractedDocument previous = existing.get();
            if (type == DocumentType.CIN && previous.getDocumentType() == DocumentType.CIN && previous.isBackSideCaptured()) {
                // Keys the new front result doesn't have are the back side's: carry them over.
                ObjectNode old = readFields(previous);
                JsonNode oldConfidence = old.path(FIELD_CONFIDENCE_KEY);
                old.fields().forEachRemaining(e -> {
                    if (!NON_FIELD_KEYS.contains(e.getKey()) && !fields.has(e.getKey())) {
                        fields.set(e.getKey(), e.getValue());
                        if (oldConfidence.has(e.getKey())) {
                            fields.withObject(FIELD_CONFIDENCE_KEY).set(e.getKey(), oldConfidence.get(e.getKey()));
                        }
                    }
                });
                backCaptured = true;
            }
            // Retake: replace the previous (unconfirmed) extraction for this session.
            documentRepository.delete(previous);
            documentRepository.flush();
        }
        refreshOverallConfidence(fields);

        ExtractedDocument document = ExtractedDocument.builder()
                .session(session)
                .documentType(type)
                .extractedFieldsJson(fields.toString())
                .ocrConfidence(fields.path(OVERALL_CONFIDENCE_KEY).asDouble(0.0))
                .checksumValid(fields.hasNonNull("checksum_valid") ? fields.get("checksum_valid").asBoolean() : null)
                .backSideCaptured(backCaptured)
                .build();
        applyKeyFields(document, fields, false);
        return document;
    }

    /** Adds (or, on a retake, replaces) the CIN back-side fields on the existing document. */
    private ExtractedDocument mergeBackSide(ExtractedDocument document, ObjectNode extraction) {
        ObjectNode fields = readFields(document);
        ObjectNode confidence = fields.withObject(FIELD_CONFIDENCE_KEY);
        extraction.fields().forEachRemaining(e -> {
            if (!NON_FIELD_KEYS.contains(e.getKey())) {
                fields.set(e.getKey(), e.getValue());
            }
        });
        extraction.path(FIELD_CONFIDENCE_KEY).fields().forEachRemaining(e -> confidence.set(e.getKey(), e.getValue()));
        refreshOverallConfidence(fields);

        document.setExtractedFieldsJson(fields.toString());
        document.setOcrConfidence(fields.path(OVERALL_CONFIDENCE_KEY).asDouble(0.0));
        document.setBackSideCaptured(true);
        return document;
    }

    /**
     * With per-field scores, the document is as confident as its weakest field -- across both
     * sides of a CIN. Results without per-field scores keep the AI service's overall value.
     */
    private static void refreshOverallConfidence(ObjectNode fields) {
        Map<String, Double> perField = fieldConfidence(fields);
        if (!perField.isEmpty()) {
            fields.put(OVERALL_CONFIDENCE_KEY, perField.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0));
        }
    }

    private void requireFrontForBack(OnboardingSession session, DocumentType type) {
        if (type != DocumentType.CIN) {
            throw new IllegalArgumentException("Only the national ID card (CIN) has a back side to photograph");
        }
        boolean frontIsCin = documentRepository.findBySessionId(session.getId())
                .map(doc -> doc.getDocumentType() == DocumentType.CIN)
                .orElse(false);
        if (!frontIsCin) {
            throw new InvalidSessionStateException("Photograph the front of your ID card first");
        }
    }

    public DocumentResponse getDocument(UUID sessionId, User user) {
        OnboardingSession session = onboardingService.getOwnedSession(sessionId, user);
        return toResponse(findDocument(session), session);
    }

    public DocumentResponse confirmDocument(UUID sessionId, User user, Map<String, String> submitted) {
        OnboardingSession session = onboardingService.getOwnedSession(sessionId, user);

        return transactionTemplate.execute(status -> {
            ExtractedDocument document = findDocument(session);
            if (session.getStatus() != SessionStatus.PENDING_REVIEW || document.getConfirmedAt() != null) {
                throw new InvalidSessionStateException("This document has already been confirmed");
            }
            if (backSideRequired(document) && !document.isBackSideCaptured()) {
                throw new InvalidSessionStateException("Photograph the back of your ID card before confirming");
            }

            ObjectNode stored = readFields(document);
            boolean corrected = false;
            for (Map.Entry<String, String> entry : submitted.entrySet()) {
                String key = entry.getKey();
                if (NON_FIELD_KEYS.contains(key) || !stored.has(key)) {
                    throw new IllegalArgumentException("Unknown field for this document: " + key);
                }
                String value = normalize(entry.getValue());
                String previous = stored.get(key).isNull() ? null : stored.get(key).asText();
                if (!Objects.equals(value, previous)) {
                    corrected = true;
                    if (value == null) {
                        stored.putNull(key);
                    } else {
                        stored.put(key, value);
                    }
                }
            }

            document.setExtractedFieldsJson(stored.toString());
            applyKeyFields(document, stored, true);
            requireNotDuplicate(document);
            document.setUserCorrected(document.isUserCorrected() || corrected);
            document.setConfirmedAt(Instant.now());

            if (computeWarnings(document).isEmpty()) {
                document.setReviewStatus(ReviewStatus.APPROVED);
                session.setStatus(SessionStatus.APPROVED);
            } else {
                // Stays PENDING_REVIEW; confirmedAt tells the admin queue the user is done.
                document.setReviewStatus(ReviewStatus.NEEDS_REVIEW);
            }
            documentRepository.save(document);
            return toResponse(document, sessionRepository.save(session));
        });
    }

    private void requireUploadAllowed(OnboardingSession session) {
        if (!session.isConsentGiven()) {
            throw new InvalidSessionStateException("Consent must be given before uploading a document");
        }
        boolean retakeAllowed = session.getStatus() == SessionStatus.PENDING_REVIEW
                && documentRepository.findBySessionId(session.getId())
                        .map(doc -> doc.getConfirmedAt() == null)
                        .orElse(true);
        if (session.getStatus() != SessionStatus.CONSENT_GIVEN && !retakeAllowed) {
            throw new InvalidSessionStateException(
                    "A document can't be uploaded while the session is " + session.getStatus());
        }
    }

    private static void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("An image file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image uploads are supported (got " + contentType + ")");
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file", e);
        }
    }

    /**
     * Copies the fields the backend indexes/checks on into real columns. When the user
     * supplied the values (strict = true), a malformed date is a 400; values straight from
     * the AI service are already ISO or null, so anything unparseable is just dropped.
     */
    private static void applyKeyFields(ExtractedDocument document, ObjectNode fields, boolean strict) {
        document.setDocumentNumber(textOrNull(fields, "document_number"));
        document.setDateOfBirth(parseDate(fields, "date_of_birth", strict));
        document.setExpiryDate(parseDate(fields, "expiry_date", strict));
    }

    private void requireNotDuplicate(ExtractedDocument document) {
        if (document.getDocumentNumber() == null) {
            return;
        }
        documentRepository.findByDocumentNumber(document.getDocumentNumber())
                .filter(other -> !other.getSession().getId().equals(document.getSession().getId()))
                .ifPresent(other -> {
                    throw new DuplicateDocumentException(
                            "This document is already registered to another onboarding application");
                });
    }

    private List<DocumentWarning> computeWarnings(ExtractedDocument document) {
        List<DocumentWarning> warnings = new ArrayList<>();
        // Any single weak field counts: a confidently-read document with one garbled name
        // must not be auto-approved just because the document-level score looks fine.
        boolean weakField = fieldConfidence(readFields(document)).values().stream().anyMatch(c -> c < minConfidence);
        if (document.getOcrConfidence() == null || document.getOcrConfidence() < minConfidence || weakField) {
            warnings.add(DocumentWarning.LOW_CONFIDENCE);
        }
        if (Boolean.FALSE.equals(document.getChecksumValid())) {
            warnings.add(DocumentWarning.CHECKSUM_FAILED);
        }
        boolean missing = document.getDocumentNumber() == null
                || document.getDateOfBirth() == null
                || (document.getDocumentType() == DocumentType.PASSPORT && document.getExpiryDate() == null);
        if (missing) {
            warnings.add(DocumentWarning.MISSING_REQUIRED_FIELDS);
        }
        if (document.getExpiryDate() != null && document.getExpiryDate().isBefore(LocalDate.now())) {
            warnings.add(DocumentWarning.DOCUMENT_EXPIRED);
        }
        if (document.isUserCorrected()) {
            warnings.add(DocumentWarning.USER_CORRECTED);
        }
        return warnings;
    }

    private ExtractedDocument findDocument(OnboardingSession session) {
        return documentRepository.findBySessionId(session.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No document uploaded for this session yet"));
    }

    private ObjectNode readFields(ExtractedDocument document) {
        try {
            JsonNode node = objectMapper.readTree(document.getExtractedFieldsJson());
            return node instanceof ObjectNode obj ? obj : objectMapper.createObjectNode();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored extraction JSON is corrupt for document " + document.getId(), e);
        }
    }

    private static boolean backSideRequired(ExtractedDocument document) {
        return document.getDocumentType() == DocumentType.CIN;
    }

    /** Per-field OCR confidence from the AI service; empty for extractions that predate it. */
    private static Map<String, Double> fieldConfidence(ObjectNode stored) {
        Map<String, Double> confidence = new LinkedHashMap<>();
        JsonNode node = stored.path(FIELD_CONFIDENCE_KEY);
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    confidence.put(e.getKey(), e.getValue().asDouble());
                }
            });
        }
        return confidence;
    }

    private DocumentResponse toResponse(ExtractedDocument document, OnboardingSession session) {
        Map<String, String> fields = new LinkedHashMap<>();
        ObjectNode stored = readFields(document);
        Iterator<Map.Entry<String, JsonNode>> it = stored.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (!NON_FIELD_KEYS.contains(entry.getKey())) {
                fields.put(entry.getKey(), entry.getValue().isNull() ? null : entry.getValue().asText());
            }
        }

        return new DocumentResponse(
                document.getId(),
                session.getId(),
                session.getStatus(),
                document.getDocumentType(),
                fields,
                fieldConfidence(stored),
                document.getDocumentNumber(),
                document.getDateOfBirth(),
                document.getExpiryDate(),
                document.getOcrConfidence(),
                document.getChecksumValid(),
                document.isUserCorrected(),
                backSideRequired(document),
                document.isBackSideCaptured(),
                computeWarnings(document),
                document.getReviewStatus(),
                document.getConfirmedAt(),
                document.getCreatedAt());
    }

    private static String textOrNull(JsonNode fields, String key) {
        JsonNode node = fields.get(key);
        return node == null || node.isNull() ? null : normalize(node.asText());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDate parseDate(JsonNode fields, String key, boolean strict) {
        String raw = textOrNull(fields, key);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            if (strict) {
                throw new IllegalArgumentException(key + " must be a date in yyyy-MM-dd format");
            }
            return null;
        }
    }
}
