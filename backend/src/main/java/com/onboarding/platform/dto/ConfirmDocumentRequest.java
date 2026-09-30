package com.onboarding.platform.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * The fields the user reviewed on the review screen, keyed exactly as the AI service
 * returned them (e.g. "surname", "date_of_birth"). Only keys that were extracted can be
 * sent; a blank value clears that field. Dates must stay ISO (yyyy-MM-dd).
 */
public record ConfirmDocumentRequest(
        @NotNull Map<String, String> fields
) {}
