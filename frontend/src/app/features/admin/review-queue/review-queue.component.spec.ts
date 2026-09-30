import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ReviewQueueItem } from '../../../core/models/admin.model';
import { ReviewQueueComponent } from './review-queue.component';

const ITEM: ReviewQueueItem = {
  documentId: 'd-1',
  sessionId: 's-1',
  applicantEmail: 'applicant@example.com',
  documentType: 'CIN',
  reviewStatus: 'NEEDS_REVIEW',
  warnings: ['LOW_CONFIDENCE', 'USER_CORRECTED'],
  ocrConfidence: 0.49,
  submittedAt: '2026-09-30T10:00:00Z',
  reviewedBy: null,
  reviewedAt: null
};

describe('ReviewQueueComponent', () => {
  let fixture: ComponentFixture<ReviewQueueComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReviewQueueComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReviewQueueComponent);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  it('lists documents waiting for review with their warnings and weakest confidence', () => {
    httpMock.expectOne('/api/admin/reviews?status=NEEDS_REVIEW').flush([ITEM]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('applicant@example.com');
    expect(text).toContain('National ID card (CIN)');
    expect(text).toContain('Low confidence');
    expect(text).toContain('Edited by applicant');
    expect(text).toContain('49%');
    expect(fixture.nativeElement.querySelector('a.item').getAttribute('href')).toBe('/admin/reviews/d-1');
  });

  it('switches to decided documents', () => {
    httpMock.expectOne('/api/admin/reviews?status=NEEDS_REVIEW').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nothing to review');

    (fixture.nativeElement.querySelectorAll('[role=tab]')[2] as HTMLButtonElement).click();
    httpMock.expectOne('/api/admin/reviews?status=REJECTED').flush([{ ...ITEM, reviewStatus: 'REJECTED', reviewedBy: 'admin@x' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('decided by admin@x');
  });
});
