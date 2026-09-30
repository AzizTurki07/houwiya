import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ReviewQueueItem } from '../../../core/models/admin.model';
import { ReviewStatus } from '../../../core/models/onboarding.model';
import { AdminService } from '../../../core/services/admin.service';
import { errorMessage } from '../../../core/utils/http-error';
import { DOCUMENT_LABEL, WARNING_ADMIN_TEXT, WARNING_SHORT } from '../../onboarding/document-fields';

type Tab = Extract<ReviewStatus, 'NEEDS_REVIEW' | 'APPROVED' | 'REJECTED'>;

@Component({
  selector: 'app-review-queue',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './review-queue.component.html',
  styleUrl: './review-queue.component.scss'
})
export class ReviewQueueComponent implements OnInit {
  private readonly admin = inject(AdminService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly tabs: { status: Tab; label: string }[] = [
    { status: 'NEEDS_REVIEW', label: 'To review' },
    { status: 'APPROVED', label: 'Approved' },
    { status: 'REJECTED', label: 'Rejected' }
  ];
  readonly status = signal<Tab>('NEEDS_REVIEW');
  readonly items = signal<ReviewQueueItem[] | null>(null);
  readonly error = signal<string | null>(null);
  readonly documentLabel = DOCUMENT_LABEL;
  readonly warningText = WARNING_ADMIN_TEXT;
  readonly warningShort = WARNING_SHORT;

  ngOnInit(): void {
    const tab = this.route.snapshot.queryParamMap.get('status');
    if (tab === 'APPROVED' || tab === 'REJECTED') {
      this.status.set(tab);
    }
    this.load();
  }

  select(status: Tab): void {
    if (status === this.status()) {
      return;
    }
    this.status.set(status);
    // Keep the tab in the URL so "back" from a review lands on the same list.
    this.router.navigate([], { queryParams: { status: status === 'NEEDS_REVIEW' ? null : status }, replaceUrl: true });
    this.load();
  }

  private load(): void {
    this.items.set(null);
    this.error.set(null);
    this.admin.queue(this.status()).subscribe({
      next: (items) => this.items.set(items),
      error: (err) => this.error.set(errorMessage(err, "Couldn't load the review queue."))
    });
  }
}
