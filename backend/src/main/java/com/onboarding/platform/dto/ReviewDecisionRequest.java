package com.onboarding.platform.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A reviewer's decision. A reason is required to reject: it is shown to the applicant. */
public record ReviewDecisionRequest(
        @NotNull Decision decision,
        @Size(max = 1000) String reason
) {
    public enum Decision { APPROVE, REJECT }
}
