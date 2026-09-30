package com.onboarding.platform.enums;

/**
 * Reasons a document can't be auto-approved. Any warning sends it to the admin review
 * queue (ReviewStatus.NEEDS_REVIEW); the frontend also uses them to highlight fields.
 */
public enum DocumentWarning {
    LOW_CONFIDENCE,          // OCR confidence below app.review.min-confidence
    CHECKSUM_FAILED,         // an MRZ check digit didn't match (passport only)
    MISSING_REQUIRED_FIELDS, // document number / date of birth (/ expiry for passports) not read
    DOCUMENT_EXPIRED,
    USER_CORRECTED           // the user edited an extracted value before confirming
}
