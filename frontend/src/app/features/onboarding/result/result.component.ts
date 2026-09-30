import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DocumentResponse } from '../../../core/models/onboarding.model';
import { OnboardingService } from '../../../core/services/onboarding.service';
import { errorMessage } from '../../../core/utils/http-error';
import { DOCUMENT_NOUN, WARNING_TEXT } from '../document-fields';
import { StepsComponent } from '../steps.component';

@Component({
  selector: 'app-result',
  standalone: true,
  imports: [RouterLink, StepsComponent],
  templateUrl: './result.component.html',
  styleUrl: './result.component.scss'
})
export class ResultComponent implements OnInit {
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly sessionId = inject(ActivatedRoute).snapshot.paramMap.get('id')!;

  readonly document = signal<DocumentResponse | null>(null);
  readonly error = signal<string | null>(null);
  readonly documentNoun = DOCUMENT_NOUN;
  readonly warningText = WARNING_TEXT;

  ngOnInit(): void {
    this.onboarding.getDocument(this.sessionId).subscribe({
      next: (doc) => {
        if (!doc.confirmedAt && doc.sessionStatus === 'PENDING_REVIEW') {
          // Not submitted yet -- the user belongs on the review screen.
          this.router.navigate(['/onboarding', this.sessionId, 'review'], { replaceUrl: true });
          return;
        }
        this.document.set(doc);
      },
      error: (err) => this.error.set(errorMessage(err, "Couldn't load this verification."))
    });
  }
}
