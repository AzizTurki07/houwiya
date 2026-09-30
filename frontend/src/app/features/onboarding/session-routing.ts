import { SessionStatus } from '../../core/models/onboarding.model';

/** Where to resume a session, based on how far it got. */
export function routeForSession(id: string, status: SessionStatus): string[] {
  switch (status) {
    case 'STARTED':
      return ['/onboarding', id, 'consent'];
    case 'CONSENT_GIVEN':
      return ['/onboarding', id, 'capture'];
    case 'DOCUMENT_UPLOADED':
    case 'PENDING_REVIEW':
      // The review screen forwards to the result page itself once the user has confirmed.
      return ['/onboarding', id, 'review'];
    case 'APPROVED':
    case 'REJECTED':
      return ['/onboarding', id, 'result'];
  }
}

export const STATUS_LABEL: Record<SessionStatus, { text: string; tone: string }> = {
  STARTED: { text: 'Not started', tone: '' },
  CONSENT_GIVEN: { text: 'Awaiting document', tone: 'primary' },
  DOCUMENT_UPLOADED: { text: 'Processing', tone: 'primary' },
  PENDING_REVIEW: { text: 'In review', tone: 'warning' },
  APPROVED: { text: 'Verified', tone: 'success' },
  REJECTED: { text: 'Rejected', tone: 'danger' }
};
