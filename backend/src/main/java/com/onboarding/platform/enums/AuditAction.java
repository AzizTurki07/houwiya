package com.onboarding.platform.enums;

public enum AuditAction {
    USER_REGISTERED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ADMIN_ACCOUNT_BOOTSTRAPPED,
    SESSION_CREATED,
    CONSENT_GIVEN,
    DOCUMENT_UPLOADED,
    DOCUMENT_CONFIRMED,
    SELFIE_CHECKED,
    SESSION_DELETED,
    REVIEW_OPENED,          // an admin viewed a document's personal data
    REVIEW_IMAGE_VIEWED,    // an admin viewed a document photo
    REVIEW_DECIDED,
    IMAGES_PURGED           // retention clean-up
}
