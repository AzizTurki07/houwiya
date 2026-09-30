import { DocumentType, DocumentWarning } from '../../core/models/onboarding.model';

export interface FieldSpec {
  key: string;
  label: string;
  type: 'text' | 'date' | 'textarea';
  required?: boolean;
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
  { key: 'nationality', label: 'Nationality (3-letter code)', type: 'text', uppercase: true, pattern: /^[A-Z]{3}$/, patternHint: 'e.g. TUN', maxLength: 3 },
  { key: 'date_of_birth', label: 'Date of birth', type: 'date', required: true },
  { key: 'sex', label: 'Sex', type: 'text', uppercase: true, pattern: /^[MFX<]$/, patternHint: 'M, F or X', maxLength: 1 },
  { key: 'expiry_date', label: 'Expiry date', type: 'date', required: true }
];

const CIN_FIELDS: FieldSpec[] = [
  { key: 'last_name', label: 'Last name', type: 'text' },
  { key: 'first_name', label: 'First name', type: 'text' },
  {
    key: 'document_number', label: 'CIN number', type: 'text', required: true,
    pattern: /^\d{8}$/, patternHint: 'exactly 8 digits', maxLength: 8
  },
  { key: 'date_of_birth', label: 'Date of birth', type: 'date', required: true },
  { key: 'place_of_birth', label: 'Place of birth', type: 'text' },
  { key: 'address', label: 'Address', type: 'textarea' }
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

/** Which fields each backend warning points at, so the review screen can highlight them. */
export function flaggedFields(warnings: DocumentWarning[], values: Record<string, string | null>, specs: FieldSpec[]): Map<string, string> {
  const flags = new Map<string, string>();
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
