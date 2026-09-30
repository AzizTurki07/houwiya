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
  readonly statusLabel = STATUS_LABEL;
  readonly routeFor = routeForSession;

  ngOnInit(): void {
    this.onboarding.listSessions().subscribe({
      next: (list) => this.sessions.set([...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt))),
      error: (err) => this.error.set(errorMessage(err, "Couldn't load your verifications."))
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
