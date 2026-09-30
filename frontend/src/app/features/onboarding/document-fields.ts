import { DocumentType, DocumentWarning } from '../../core/models/onboarding.model';

/** Matches app.review.min-confidence on the backend: below this, a human has to check it. */
export const REVIEW_THRESHOLD = 0.7;

export type ConfidenceLevel = 'high' | 'medium' | 'low';

export function confidenceLevel(confidence: number): ConfidenceLevel {
  return confidence >= 0.85 ? 'high' : confidence >= REVIEW_THRESHOLD ? 'medium' : 'low';
}

export interface FieldSpec {
  key: string;
  label: string;
  type: 'text' | 'date' | 'textarea';
  required?: boolean;
  /** Arabic values (the CIN) are typed and shown right-to-left. */
  rtl?: boolean;
  /** Validation pattern + message shown when it fails. */
  pattern?: RegExp;
  patternHint?: string;
  maxLength?: number;
  uppercase?: boolean;
}

// Keys match what the AI service returns (ai-service/app/schemas.py).
const PASSPORT_FIELDS: FieldSpec[] = [
  { key: 'surname', label: 'Surname', type: 'text', uppercase: true },
  { key: 'given_names', label: 'Given names', type: 'text', uppercase: true },
  {
    key: 'document_number', label: 'Passport number', type: 'text', required: true, uppercase: true,
    pattern: /^[A-Z0-9]{5,9}$/, patternHint: '5–9 letters or digits', maxLength: 9
  },
  // ICAO codes are padded with '<' when shorter than 3 letters (Germany is "D<<").
  { key: 'nationality', label: 'Nationality (3-letter code)', type: 'text', uppercase: true, pattern: /^[A-Z][A-Z<]{2}$/, patternHint: 'e.g. TUN', maxLength: 3 },
  { key: 'date_of_birth', label: 'Date of birth', type: 'date', required: true },
  { key: 'sex', label: 'Sex', type: 'text', uppercase: true, pattern: /^[MFX<]$/, patternHint: 'M, F or X', maxLength: 1 },
  { key: 'expiry_date', label: 'Expiry date', type: 'date', required: true }
];

// Front of the card, then the back. Values are Arabic, as printed on the card.
const CIN_FIELDS: FieldSpec[] = [
  {
    key: 'document_number', label: 'CIN number', type: 'text', required: true,
    pattern: /^\d{8}$/, patternHint: 'exactly 8 digits', maxLength: 8
  },
  { key: 'last_name', label: 'Surname (اللقب)', type: 'text', rtl: true },
  { key: 'first_name', label: 'First name (الاسم)', type: 'text', rtl: true },
  { key: 'lineage', label: 'Lineage (بن / بنت …)', type: 'text', rtl: true },
  { key: 'date_of_birth', label: 'Date of birth', type: 'date', required: true },
  { key: 'place_of_birth', label: 'Place of birth (مكانها)', type: 'text', rtl: true },
  { key: 'profession', label: 'Profession (المهنة)', type: 'text', rtl: true },
  { key: 'address', label: 'Address (العنوان)', type: 'textarea', rtl: true },
  { key: 'issue_date', label: 'Issue date', type: 'date' }
];

/**
 * Fields to render for a document, in display order. Any extra key the AI service
 * returns that we don't know about yet is still shown (as plain text) rather than dropped.
 */
export function fieldSpecsFor(type: DocumentType, extractedKeys: string[]): FieldSpec[] {
  const known = type === 'PASSPORT' ? PASSPORT_FIELDS : CIN_FIELDS;
  const knownKeys = new Set(known.map((f) => f.key));
  const extras: FieldSpec[] = extractedKeys
    .filter((k) => !knownKeys.has(k))
    .map((k) => ({ key: k, label: humanize(k), type: 'text' }));
  return [...known.filter((f) => extractedKeys.includes(f.key)), ...extras];
}

function humanize(key: string): string {
  const words = key.replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * Which fields need the user's attention, and why, so the review screen can highlight them.
 * Specific reasons (failed check digit, missing, expired) win over a generic "hard to read".
 */
export function flaggedFields(
  warnings: DocumentWarning[],
  values: Record<string, string | null>,
  specs: FieldSpec[],
  fieldConfidence: Record<string, number> = {}
): Map<string, string> {
  const flags = new Map<string, string>();
  for (const spec of specs) {
    const confidence = fieldConfidence[spec.key];
    // Unread optional fields score 0 but are empty -- nothing to double-check there.
    if (confidence !== undefined && confidence < REVIEW_THRESHOLD && values[spec.key]) {
      flags.set(spec.key, 'This was hard to read. Check it carefully against your document.');
    }
  }
  if (warnings.includes('CHECKSUM_FAILED')) {
    for (const key of ['document_number', 'date_of_birth', 'expiry_date']) {
      flags.set(key, "Didn't pass the passport's check-digit test. Compare carefully with your document.");
    }
  }
  if (warnings.includes('DOCUMENT_EXPIRED')) {
    flags.set('expiry_date', 'This document appears to be expired.');
  }
  if (warnings.includes('MISSING_REQUIRED_FIELDS')) {
    for (const spec of specs) {
      if (spec.required && !values[spec.key]) {
        flags.set(spec.key, "We couldn't read this. Please fill it in.");
      }
    }
  }
  return flags;
}

export const WARNING_TEXT: Record<DocumentWarning, string> = {
  LOW_CONFIDENCE: 'Parts of the photo were hard to read.',
  CHECKSUM_FAILED: "The passport's machine-readable zone didn't validate.",
  MISSING_REQUIRED_FIELDS: 'Some required details could not be read.',
  DOCUMENT_EXPIRED: 'The document appears to be expired.',
  USER_CORRECTED: 'You corrected some of the extracted details.'
};

export const DOCUMENT_LABEL: Record<DocumentType, string> = {
  PASSPORT: 'Passport',
  CIN: 'National ID card (CIN)'
};

/** For use mid-sentence: "We read these from your ___." */
export const DOCUMENT_NOUN: Record<DocumentType, string> = {
  PASSPORT: 'passport',
  CIN: 'national ID card'
};
