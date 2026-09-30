import { DocumentSide, DocumentType, DocumentWarning, ReviewStatus } from './onboarding.model';

// Mirrors com.onboarding.platform.dto.ReviewQueueItem / ReviewDetail / AuditEventResponse.

export interface ReviewQueueItem {
  documentId: string;
  sessionId: string;
  applicantEmail: string;
  documentType: DocumentType;
  reviewStatus: ReviewStatus;
  warnings: DocumentWarning[];
  ocrConfidence: number | null;
  submittedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
}

export interface ReviewDetail {
  documentId: string;
  sessionId: string;
  applicantEmail: string;
  documentType: DocumentType;
  reviewStatus: ReviewStatus;
  /** As confirmed by the applicant. */
  fields: Record<string, string | null>;
  /** As the OCR read them; empty unless the applicant changed something. */
  originalFields: Record<string, string | null>;
  fieldConfidence: Record<string, number>;
  ocrConfidence: number | null;
  checksumValid: boolean | null;
  expiryDate: string | null;
  warnings: DocumentWarning[];
  /** Photos still stored -- they are deleted once a decision is made. */
  imageSides: DocumentSide[];
  submittedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  decisionReason: string | null;
}

export type ReviewDecision = 'APPROVE' | 'REJECT';

export interface AuditEvent {
  id: string;
  occurredAt: string;
  actor: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  details: string | null;
}
