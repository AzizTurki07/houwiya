// Mirrors the backend DTOs in com.onboarding.platform.dto / .enums.

export type DocumentType = 'PASSPORT' | 'CIN';

/** The CIN needs both; a passport only has a front (the photo page). */
export type DocumentSide = 'FRONT' | 'BACK';

export type SessionStatus =
  | 'STARTED'
  | 'CONSENT_GIVEN'
  | 'DOCUMENT_UPLOADED'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED';

export type ReviewStatus = 'PENDING' | 'NEEDS_REVIEW' | 'APPROVED' | 'REJECTED';

export type DocumentWarning =
  | 'LOW_CONFIDENCE'
  | 'CHECKSUM_FAILED'
  | 'MISSING_REQUIRED_FIELDS'
  | 'DOCUMENT_EXPIRED'
  | 'USER_CORRECTED';

export interface SessionResponse {
  id: string;
  status: SessionStatus;
  consentGiven: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentResponse {
  id: string;
  sessionId: string;
  sessionStatus: SessionStatus;
  documentType: DocumentType;
  /** Editable fields keyed as the AI service returned them, e.g. "surname", "date_of_birth". */
  fields: Record<string, string | null>;
  /** 0-1 OCR confidence per key of `fields`. A key can be missing (e.g. older extractions). */
  fieldConfidence: Record<string, number>;
  documentNumber: string | null;
  dateOfBirth: string | null;
  expiryDate: string | null;
  ocrConfidence: number | null;
  checksumValid: boolean | null;
  userCorrected: boolean;
  /** CIN: address/profession/issue date are on the back, a second photo. */
  backSideRequired: boolean;
  backSideCaptured: boolean;
  warnings: DocumentWarning[];
  reviewStatus: ReviewStatus;
  /** Why an admin rejected it; only set when rejected. */
  decisionReason: string | null;
  confirmedAt: string | null;
  createdAt: string;
}

export interface ConfirmDocumentRequest {
  fields: Record<string, string | null>;
}
