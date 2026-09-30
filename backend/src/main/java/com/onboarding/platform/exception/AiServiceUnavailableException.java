package com.onboarding.platform.exception;

/** Mapped to 502: the AI service is down, timed out, or returned an unexpected error. */
public class AiServiceUnavailableException extends RuntimeException {
    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
