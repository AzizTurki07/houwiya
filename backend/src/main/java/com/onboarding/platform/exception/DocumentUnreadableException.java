package com.onboarding.platform.exception;

/**
 * Mapped to 422: the AI service couldn't find an MRZ / card outline in the image.
 * The frontend should treat this as a "retake the photo" prompt, not a generic error.
 */
public class DocumentUnreadableException extends RuntimeException {
    public DocumentUnreadableException(String message) {
        super(message);
    }
}
