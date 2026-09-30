import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { ReviewDetail } from '../../../core/models/admin.model';
import { ReviewDetailComponent } from './review-detail.component';

const BASE = '/api/admin/reviews/d-1';

// Fictitious values (same as the AI service's synthetic fixture).
function detail(overrides: Partial<ReviewDetail> = {}): ReviewDetail {
  return {
    documentId: 'd-1',
    sessionId: 's-1',
    applicantEmail: 'applicant@example.com',
    documentType: 'CIN',
    reviewStatus: 'NEEDS_REVIEW',
    fields: { document_number: '07845213', first_name: 'أمين', last_name: 'بن سالم' },
    originalFields: { document_number: '07845213', first_name: 'امبن', last_name: 'بن سالم' },
    fieldConfidence: { document_number: 0.95, first_name: 0.41, last_name: 0.9 },
    ocrConfidence: 0.41,
    checksumValid: null,
    expiryDate: null,
    warnings: ['LOW_CONFIDENCE', 'USER_CORRECTED'],
    imageSides: ['FRONT', 'BACK'],
    submittedAt: '2026-09-30T10:00:00Z',
    reviewedBy: null,
    reviewedAt: null,
    decisionReason: null,
    ...overrides
  };
}

describe('ReviewDetailComponent', () => {
  let fixture: ComponentFixture<ReviewDetailComponent>;
  let component: ReviewDetailComponent;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReviewDetailComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ documentId: 'd-1' }) } } }
      ]
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReviewDetailComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  function load(d = detail()): void {
    httpMock.expectOne(BASE).flush(d);
    for (const side of d.imageSides) {
      httpMock.expectOne(`${BASE}/images/${side}`).flush(new Blob(['img'], { type: 'image/jpeg' }));
    }
    fixture.detectChanges();
  }

  it('shows both photos and what the applicant changed', () => {
    load();

    expect(fixture.nativeElement.querySelectorAll('.photos img').length).toBe(2);
    const changed = fixture.nativeElement.querySelectorAll('tr.changed');
    expect(changed.length).toBe(1);
    expect(changed[0].textContent).toContain('First name');
    expect(changed[0].textContent).toContain('OCR read');
    expect(changed[0].textContent).toContain('امبن');
    expect(fixture.nativeElement.querySelector('tr.changed .dot').className).toContain('low');
  });

  it('will not reject without a reason for the applicant', () => {
    load();
    component.decide('REJECT');
    httpMock.expectNone(`${BASE}/decision`);
    expect(component.decisionError()).toContain('reason');
  });

  it('records a decision, then shows it and drops the (now deleted) photos', () => {
    load();
    component.reason = 'Name unreadable ';
    component.decide('REJECT');

    const req = httpMock.expectOne(`${BASE}/decision`);
    expect(req.request.body).toEqual({ decision: 'REJECT', reason: 'Name unreadable' });
    req.flush(detail({ reviewStatus: 'REJECTED', imageSides: [], reviewedBy: 'admin@x', decisionReason: 'Name unreadable' }));
    fixture.detectChanges();

    expect(component.photos().length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Decision recorded');
    expect(fixture.nativeElement.textContent).toContain('Rejected by admin@x');
    expect(fixture.nativeElement.querySelector('#reason')).toBeNull();
  });

  it('approves without requiring a reason', () => {
    load();
    component.decide('APPROVE');
    const req = httpMock.expectOne(`${BASE}/decision`);
    expect(req.request.body).toEqual({ decision: 'APPROVE', reason: null });
    req.flush(detail({ reviewStatus: 'APPROVED', imageSides: [] }));
  });
});
