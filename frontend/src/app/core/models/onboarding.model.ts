// Mirrors the backend DTOs in com.onboarding.platform.dto / .enums.

export type DocumentType = 'PASSPORT' | 'CIN';

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
  documentNumber: string | null;
  dateOfBirth: string | null;
  expiryDate: string | null;
  ocrConfidence: number | null;
  checksumValid: boolean | null;
  userCorrected: boolean;
  warnings: DocumentWarning[];
  reviewStatus: ReviewStatus;
  confirmedAt: string | null;
  createdAt: string;
}

export interface ConfirmDocumentRequest {
  fields: Record<string, string | null>;
}
