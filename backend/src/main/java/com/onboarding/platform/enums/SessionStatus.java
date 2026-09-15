package com.onboarding.platform.enums;

public enum SessionStatus {
    STARTED,            // session created, consent not yet given
    CONSENT_GIVEN,       // ready for document upload
    DOCUMENT_UPLOADED,   // image received, extraction in progress
    PENDING_REVIEW,      // extracted, waiting on user confirm or admin review
    APPROVED,
    REJECTED
}
