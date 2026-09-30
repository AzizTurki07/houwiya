package com.onboarding.platform.config;

import com.onboarding.platform.exception.AiServiceUnavailableException;
import com.onboarding.platform.exception.DocumentUnreadableException;
import com.onboarding.platform.exception.DuplicateDocumentException;
import com.onboarding.platform.exception.InvalidSessionStateException;
import com.onboarding.platform.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<String> handleBadCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler({InvalidSessionStateException.class, DuplicateDocumentException.class})
    public ResponseEntity<String> handleConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    // 422 = "retake the photo" for the frontend.
    @ExceptionHandler(DocumentUnreadableException.class)
    public ResponseEntity<String> handleUnreadable(DocumentUnreadableException ex) {
        return ResponseEntity.unprocessableEntity().body(ex.getMessage());
    }

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<String> handleAiUnavailable() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("Document extraction is temporarily unavailable. Please try again shortly.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("Image is too large (max 10MB)");
    }
}
