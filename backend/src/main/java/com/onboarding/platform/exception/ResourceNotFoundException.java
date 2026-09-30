package com.onboarding.platform.exception;

/** Mapped to 404. Also used for "exists but isn't yours" so session IDs can't be probed. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
