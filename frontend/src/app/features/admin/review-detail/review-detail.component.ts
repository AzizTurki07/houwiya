import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ReviewDecision, ReviewDetail } from '../../../core/models/admin.model';
import { DocumentSide } from '../../../core/models/onboarding.model';
import { AdminService } from '../../../core/services/admin.service';
import { errorMessage } from '../../../core/utils/http-error';
import { ConfidenceLevel, DOCUMENT_LABEL, FieldSpec, confidenceLevel, fieldSpecsFor, WARNING_ADMIN_TEXT } from '../../onboarding/document-fields';

interface FieldRow {
  spec: FieldSpec;
  value: string | null;
  /** What the OCR read, when the applicant changed it. */
  original: string | null | undefined;
  changed: boolean;
  confidence: { level: ConfidenceLevel; pct: number } | null;
}

const PHOTO_ORDER: DocumentSide[] = ['FRONT', 'BACK', 'SELFIE'];
const PHOTO_CAPTION: Record<DocumentSide, string> = { FRONT: 'Front', BACK: 'Back', SELFIE: 'Selfie (live)' };

@Component({
  selector: 'app-review-detail',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './review-detail.component.html',
  styleUrl: './review-detail.component.scss'
})
export class ReviewDetailComponent implements OnInit, OnDestroy {
  private readonly admin = inject(AdminService);
  private readonly documentId = inject(ActivatedRoute).snapshot.paramMap.get('documentId')!;

  readonly detail = signal<ReviewDetail | null>(null);
  readonly photos = signal<{ side: DocumentSide; url: string }[]>([]);
  readonly error = signal<string | null>(null);
  readonly decisionError = signal<string | null>(null);
  readonly deciding = signal<ReviewDecision | null>(null);
  readonly justDecided = signal(false);
  reason = '';

  readonly documentLabel = DOCUMENT_LABEL;
  readonly warningText = WARNING_ADMIN_TEXT;
  readonly photoCaption = PHOTO_CAPTION;

  readonly rows = computed<FieldRow[]>(() => {
    const d = this.detail();
    if (!d) {
      return [];
    }
    return fieldSpecsFor(d.documentType, Object.keys(d.fields)).map((spec) => {
      const value = d.fields[spec.key] ?? null;
      const hasOriginal = Object.prototype.hasOwnProperty.call(d.originalFields, spec.key);
      const original = hasOriginal ? d.originalFields[spec.key] ?? null : undefined;
      const conf = d.fieldConfidence[spec.key];
      return {
        spec,
        value,
        original,
        changed: hasOriginal && original !== value,
        confidence: conf === undefined ? null : { level: confidenceLevel(conf), pct: Math.round(conf * 100) }
      };
    });
  });

  ngOnInit(): void {
    this.admin.open(this.documentId).subscribe({
      next: (d) => this.show(d),
      error: (err) => this.error.set(errorMessage(err, "Couldn't open this document."))
    });
  }

  ngOnDestroy(): void {
    this.releasePhotos();
  }

  decide(decision: ReviewDecision): void {
    const reason = this.reason.trim() || null;
    if (decision === 'REJECT' && !reason) {
      this.decisionError.set('Give the applicant a reason: it is shown to them.');
      return;
    }
    this.deciding.set(decision);
    this.decisionError.set(null);
    this.admin.decide(this.documentId, decision, reason).subscribe({
      next: (d) => {
        this.deciding.set(null);
        this.justDecided.set(true);
        this.show(d);
      },
      error: (err) => {
        this.deciding.set(null);
        this.decisionError.set(errorMessage(err, "Couldn't record the decision."));
      }
    });
  }

  private show(d: ReviewDetail): void {
    this.detail.set(d);
    this.releasePhotos();
    // Photos are decrypted on request; front first. Once decided they no longer exist.
    for (const side of [...d.imageSides].sort()) {
      this.admin.image(this.documentId, side).subscribe({
        next: (blob) => this.photos.update((p) => [...p, { side, url: URL.createObjectURL(blob) }]
          .sort((a, b) => PHOTO_ORDER.indexOf(a.side) - PHOTO_ORDER.indexOf(b.side))),
        error: () => undefined
      });
    }
  }

  private releasePhotos(): void {
    this.photos().forEach((p) => URL.revokeObjectURL(p.url));
    this.photos.set([]);
  }
}
