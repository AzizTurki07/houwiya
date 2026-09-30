package com.onboarding.platform.exception;

/** Mapped to 409: this document number is already attached to another onboarding session. */
public class DuplicateDocumentException extends RuntimeException {
    public DuplicateDocumentException(String message) {
        super(message);
    }
}
