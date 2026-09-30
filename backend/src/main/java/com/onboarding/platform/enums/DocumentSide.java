package com.onboarding.platform.enums;

/**
 * Which photo: a face of the document (only the CIN has a back worth reading), or the
 * applicant's frontal selfie frame kept for the reviewer. SELFIE is never an upload side.
 */
public enum DocumentSide {
    FRONT,
    BACK,
    SELFIE
}
