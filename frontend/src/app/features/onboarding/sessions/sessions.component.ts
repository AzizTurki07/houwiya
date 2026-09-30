import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { SessionResponse } from '../../../core/models/onboarding.model';
import { OnboardingService } from '../../../core/services/onboarding.service';
import { errorMessage } from '../../../core/utils/http-error';
import { STATUS_LABEL, routeForSession } from '../session-routing';

@Component({
  selector: 'app-sessions',
  standalone: true,
  imports: [DatePipe, RouterLink],
  templateUrl: './sessions.component.html',
  styleUrl: './sessions.component.scss'
})
export class SessionsComponent implements OnInit {
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);

  readonly sessions = signal<SessionResponse[] | null>(null);
  readonly error = signal<string | null>(null);
  readonly starting = signal(false);
  /** Session id awaiting a second click to confirm deletion. */
  readonly confirmingDelete = signal<string | null>(null);
  readonly deleting = signal<string | null>(null);
  readonly statusLabel = STATUS_LABEL;
  readonly routeFor = routeForSession;

  ngOnInit(): void {
    this.onboarding.listSessions().subscribe({
      next: (list) => this.sessions.set([...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt))),
      error: (err) => this.error.set(errorMessage(err, "Couldn't load your verifications."))
    });
  }

  remove(id: string): void {
    this.deleting.set(id);
    this.error.set(null);
    this.onboarding.deleteSession(id).subscribe({
      next: () => {
        this.sessions.update((list) => (list ?? []).filter((s) => s.id !== id));
        this.deleting.set(null);
        this.confirmingDelete.set(null);
      },
      error: (err) => {
        this.deleting.set(null);
        this.error.set(errorMessage(err, "Couldn't delete this verification."));
      }
    });
  }

  start(): void {
    this.starting.set(true);
    this.error.set(null);
    this.onboarding.createSession().subscribe({
      next: (session) => this.router.navigate(routeForSession(session.id, session.status)),
      error: (err) => {
        this.starting.set(false);
        this.error.set(errorMessage(err, "Couldn't start a new verification."));
      }
    });
  }
}
