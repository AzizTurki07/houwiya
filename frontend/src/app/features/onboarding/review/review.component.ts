import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DocumentResponse } from '../../../core/models/onboarding.model';
import { OnboardingService } from '../../../core/services/onboarding.service';
import { errorMessage } from '../../../core/utils/http-error';
import {
  ConfidenceLevel,
  DOCUMENT_NOUN,
  FieldSpec,
  WARNING_TEXT,
  confidenceLevel,
  fieldSpecsFor,
  flaggedFields
} from '../document-fields';
import { StepsComponent } from '../steps.component';

@Component({
  selector: 'app-review',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, StepsComponent],
  templateUrl: './review.component.html',
  styleUrl: './review.component.scss'
})
export class ReviewComponent implements OnInit {
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  readonly sessionId = inject(ActivatedRoute).snapshot.paramMap.get('id')!;

  readonly document = signal<DocumentResponse | null>(null);
  readonly specs = signal<FieldSpec[]>([]);
  readonly flags = signal<Map<string, string>>(new Map());
  readonly loadError = signal<string | null>(null);
  readonly submitError = signal<string | null>(null);
  readonly submitting = signal(false);
  form = new FormGroup<Record<string, FormControl<string>>>({});

  readonly documentNoun = DOCUMENT_NOUN;
  readonly warningText = WARNING_TEXT;

  /** high / medium / low, for the confidence meter. */
  readonly confidenceLevel = computed(() => confidenceLevel(this.document()?.ocrConfidence ?? 0));
  readonly confidencePct = computed(() => Math.round((this.document()?.ocrConfidence ?? 0) * 100));

  ngOnInit(): void {
    this.onboarding.getDocument(this.sessionId).subscribe({
      next: (doc) => {
        if (doc.confirmedAt) {
          this.router.navigate(['/onboarding', this.sessionId, 'result'], { replaceUrl: true });
          return;
        }
        if (doc.backSideRequired && !doc.backSideCaptured) {
          this.router.navigate(['/onboarding', this.sessionId, 'capture-back'], { replaceUrl: true });
          return;
        }
        this.setDocument(doc);
      },
      error: (err) => {
        // No document yet (e.g. user navigated here directly) -> go take the photo.
        if (err?.status === 404 && typeof err.error === 'string' && err.error.startsWith('No document')) {
          this.router.navigate(['/onboarding', this.sessionId, 'capture'], { replaceUrl: true });
          return;
        }
        this.loadError.set(errorMessage(err, "Couldn't load the extracted details."));
      }
    });
  }

  private setDocument(doc: DocumentResponse): void {
    const specs = fieldSpecsFor(doc.documentType, Object.keys(doc.fields));
    const controls: Record<string, FormControl<string>> = {};
    for (const spec of specs) {
      controls[spec.key] = new FormControl(doc.fields[spec.key] ?? '', {
        nonNullable: true,
        validators: [fieldValidator(spec)]
      });
    }
    this.form = new FormGroup(controls);
    this.specs.set(specs);
    this.flags.set(flaggedFields(doc.warnings, doc.fields, specs, doc.fieldConfidence ?? {}));
    this.document.set(doc);
  }

  /** Per-field reading confidence for the dot next to each label; null when unknown or the field is empty. */
  fieldLevel(key: string): { level: ConfidenceLevel; pct: number } | null {
    const doc = this.document();
    const confidence = doc?.fieldConfidence?.[key];
    if (confidence === undefined || !doc?.fields[key]) {
      return null;
    }
    return { level: confidenceLevel(confidence), pct: Math.round(confidence * 100) };
  }

  showError(key: string): boolean {
    const c = this.form.controls[key];
    return !!c && c.invalid && (c.touched || c.dirty);
  }

  errorFor(spec: FieldSpec): string {
    const errors = this.form.controls[spec.key]?.errors ?? {};
    if (errors['required']) {
      return `${spec.label} is required.`;
    }
    if (errors['pattern']) {
      return `Expected ${spec.patternHint}.`;
    }
    return 'Invalid value.';
  }

  /** A flag stops showing once the user edits that field -- they've dealt with it. */
  flagFor(key: string): string | null {
    return this.form.controls[key]?.dirty ? null : this.flags().get(key) ?? null;
  }

  confirm(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.submitError.set('Please fix the highlighted fields.');
      return;
    }
    const fields: Record<string, string | null> = {};
    for (const spec of this.specs()) {
      fields[spec.key] = normalize(spec, this.form.controls[spec.key].value);
    }

    this.submitting.set(true);
    this.submitError.set(null);
    this.onboarding.confirmDocument(this.sessionId, fields).subscribe({
      next: () => this.router.navigate(['/onboarding', this.sessionId, 'result']),
      error: (err) => {
        this.submitting.set(false);
        this.submitError.set(errorMessage(err, "Couldn't submit your details. Please try again."));
      }
    });
  }
}

function normalize(spec: FieldSpec, raw: string): string | null {
  const value = raw.trim();
  if (!value) {
    return null;
  }
  return spec.uppercase ? value.toUpperCase() : value;
}

function fieldValidator(spec: FieldSpec): ValidatorFn {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const value = normalize(spec, control.value ?? '');
    if (!value) {
      return spec.required ? { required: true } : null;
    }
    if (spec.pattern && !spec.pattern.test(value)) {
      return { pattern: true };
    }
    return null;
  };
}
