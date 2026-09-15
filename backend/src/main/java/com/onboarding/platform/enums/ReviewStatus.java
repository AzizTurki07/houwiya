package com.onboarding.platform.enums;

public enum ReviewStatus {
    PENDING,        // not yet looked at
    NEEDS_REVIEW,   // low OCR confidence or failed checksum -> flagged for a human
    APPROVED,
    REJECTED
}
