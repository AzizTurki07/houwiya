import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuditEvent } from '../../../core/models/admin.model';
import { AdminService } from '../../../core/services/admin.service';
import { errorMessage } from '../../../core/utils/http-error';

/** Read-only view of the audit trail: latest events, or everything about one document/session. */
@Component({
  selector: 'app-audit-log',
  standalone: true,
  imports: [DatePipe, RouterLink],
  template: `
    <main class="page wide">
      <a class="btn link back" routerLink="/admin">← Review queue</a>
      <h1>Audit trail</h1>
      <p class="muted">
        {{ target ? 'Everything recorded about this document.' : 'The latest 100 events.' }}
        Entries hold ids and actions only, never document values.
      </p>
      @if (error()) {
        <div class="alert error" role="alert">{{ error() }}</div>
      } @else if (events() === null) {
        <p class="muted" aria-live="polite">Loading…</p>
      } @else {
        <div class="card table-wrap">
          <table>
            <thead>
              <tr><th>When</th><th>Who</th><th>Action</th><th>Details</th></tr>
            </thead>
            <tbody>
              @for (e of events(); track e.id) {
                <tr>
                  <td class="nowrap">{{ e.occurredAt | date: 'short' }}</td>
                  <td class="who">{{ e.actor ?? '—' }}</td>
                  <td><code>{{ e.action }}</code></td>
                  <td class="muted">{{ e.details ?? '' }}</td>
                </tr>
              } @empty {
                <tr><td colspan="4" class="muted">No events.</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    </main>
  `,
  styles: `
    .back { margin: 0 0 8px -8px; }
    .table-wrap { overflow-x: auto; padding: 0; }
    table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    th, td { text-align: start; padding: 10px 12px; border-bottom: 1px solid var(--border); vertical-align: top; }
    th { color: var(--text-muted); font-weight: 600; }
    .nowrap { white-space: nowrap; }
    .who { overflow-wrap: anywhere; }
  `
})
export class AuditLogComponent implements OnInit {
  private readonly admin = inject(AdminService);
  readonly target = inject(ActivatedRoute).snapshot.queryParamMap.get('target');
  readonly events = signal<AuditEvent[] | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.admin.audit(this.target ?? undefined).subscribe({
      next: (events) => this.events.set(this.target ? [...events].reverse() : events),
      error: (err) => this.error.set(errorMessage(err, "Couldn't load the audit trail."))
    });
  }
}
