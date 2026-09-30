import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { OnboardingService } from '../../../core/services/onboarding.service';
import { errorMessage } from '../../../core/utils/http-error';
import { routeForSession } from '../session-routing';
import { StepsComponent } from '../steps.component';

@Component({
  selector: 'app-consent',
  standalone: true,
  imports: [FormsModule, RouterLink, StepsComponent],
  templateUrl: './consent.component.html'
})
export class ConsentComponent implements OnInit {
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly sessionId = inject(ActivatedRoute).snapshot.paramMap.get('id')!;

  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  agreed = false;

  ngOnInit(): void {
    this.onboarding.getSession(this.sessionId).subscribe({
      next: (session) => {
        if (session.consentGiven) {
          this.router.navigate(routeForSession(session.id, session.status), { replaceUrl: true });
        } else {
          this.loading.set(false);
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.loadError.set(errorMessage(err, "Couldn't load this verification."));
      }
    });
  }

  accept(): void {
    this.submitting.set(true);
    this.error.set(null);
    this.onboarding.giveConsent(this.sessionId).subscribe({
      next: (session) => this.router.navigate(routeForSession(session.id, session.status)),
      error: (err) => {
        this.submitting.set(false);
        this.error.set(errorMessage(err));
      }
    });
  }
}
