package com.onboarding.platform.exception;

/** Mapped to 409: the requested step isn't allowed from the session's current status. */
public class InvalidSessionStateException extends RuntimeException {
    public InvalidSessionStateException(String message) {
        super(message);
    }
}
