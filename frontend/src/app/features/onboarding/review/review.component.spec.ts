import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { DocumentResponse } from '../../../core/models/onboarding.model';
import { ReviewComponent } from './review.component';

const SESSION = 's-1';
const DOC_URL = `/api/onboarding/sessions/${SESSION}/document`;
const CONFIRM_URL = `/api/onboarding/sessions/${SESSION}/confirm`;

function cinDocument(overrides: Partial<DocumentResponse> = {}): DocumentResponse {
  return {
    id: 'd-1',
    sessionId: SESSION,
    sessionStatus: 'PENDING_REVIEW',
    documentType: 'CIN',
    fields: {
      document_number: '07845213',
      last_name: 'بن سالم',
      first_name: 'أمين',
      lineage: 'بن محمد بن صالح',
      date_of_birth: '1996-09-14',
      place_of_birth: 'صفاقس',
      profession: 'مهندس',
      address: '12 نهج الحرية ساقية الزيت صفاقس',
      issue_date: '2018-03-03'
    },
    fieldConfidence: {
      document_number: 0.9, last_name: 0.9, first_name: 0.9, lineage: 0.9, date_of_birth: 0.9,
      place_of_birth: 0.9, profession: 0.9, address: 0.9, issue_date: 0.9
    },
    documentNumber: '07845213',
    dateOfBirth: '1996-09-14',
    expiryDate: null,
    ocrConfidence: 0.91,
    checksumValid: null,
    userCorrected: false,
    backSideRequired: true,
    backSideCaptured: true,
    warnings: [],
    reviewStatus: 'PENDING',
    decisionReason: null,
    confirmedAt: null,
    createdAt: '2026-09-30T10:00:00Z',
    ...overrides
  };
}

describe('ReviewComponent', () => {
  let fixture: ComponentFixture<ReviewComponent>;
  let component: ReviewComponent;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReviewComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: SESSION }) } } }
      ]
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(ReviewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges(); // ngOnInit -> GET document
  });

  afterEach(() => httpMock.verify());

  function load(doc: DocumentResponse): void {
    httpMock.expectOne(DOC_URL).flush(doc);
    fixture.detectChanges();
  }

  it('renders one editable input per extracted field, in display order', () => {
    load(cinDocument());
    const labels = Array.from(fixture.nativeElement.querySelectorAll('.field label') as NodeListOf<HTMLElement>)
      .map((l) => l.textContent!.replace('*', '').trim());
    expect(labels).toEqual([
      'CIN number', 'Surname (اللقب)', 'First name (الاسم)', 'Lineage (بن / بنت …)', 'Date of birth',
      'Place of birth (مكانها)', 'Profession (المهنة)', 'Address (العنوان)', 'Issue date'
    ]);
    expect(component.form.controls['first_name'].value).toBe('أمين');
    expect(component.confidenceLevel()).toBe('high');
  });

  it('highlights fields the backend flagged, and shows low confidence', () => {
    load(cinDocument({
      ocrConfidence: 0.4,
      warnings: ['LOW_CONFIDENCE', 'MISSING_REQUIRED_FIELDS'],
      fields: { ...cinDocument().fields, date_of_birth: null }
    }));

    expect(component.confidenceLevel()).toBe('low');
    expect(component.flagFor('date_of_birth')).toContain("couldn't read");
    expect(fixture.nativeElement.querySelectorAll('.field.flagged').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Retake the photo');
  });

  it('blocks submission when a CIN number is not 8 digits', () => {
    load(cinDocument());
    component.form.controls['document_number'].setValue('1234');

    component.confirm();

    httpMock.expectNone(CONFIRM_URL);
    expect(component.submitError()).toContain('fix the highlighted');
    expect(component.errorFor(component.specs().find((s) => s.key === 'document_number')!)).toContain('8 digits');
  });

  it('sends trimmed values, with blanks as null, then goes to the result page', () => {
    load(cinDocument());
    component.form.controls['first_name'].setValue('  Amin  ');
    component.form.controls['address'].setValue('   ');

    component.confirm();

    const req = httpMock.expectOne(CONFIRM_URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.fields.first_name).toBe('Amin');
    expect(req.request.body.fields.address).toBeNull();
    expect(req.request.body.fields.document_number).toBe('07845213');
    req.flush(cinDocument({ confirmedAt: '2026-09-30T10:05:00Z', sessionStatus: 'APPROVED' }));

    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'result']);
  });

  it('shows the backend reason when confirming fails (e.g. duplicate document)', () => {
    load(cinDocument());
    component.confirm();
    httpMock.expectOne(CONFIRM_URL).flush('This document is already registered to another onboarding application',
      { status: 409, statusText: 'Conflict' });

    expect(component.submitError()).toContain('already registered');
    expect(component.submitting()).toBeFalse();
  });

  it('forwards to the result page when the document was already confirmed', () => {
    load(cinDocument({ confirmedAt: '2026-09-30T10:05:00Z' }));
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'result'], { replaceUrl: true });
  });

  it('sends the user to photograph the back when a CIN has only its front', () => {
    load(cinDocument({ backSideCaptured: false }));
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'capture-back'], { replaceUrl: true });
  });

  it('offers separate retakes for both sides of a CIN', () => {
    load(cinDocument());
    const links = Array.from(fixture.nativeElement.querySelectorAll('a.btn') as NodeListOf<HTMLElement>).map((a) => a.textContent!.trim());
    expect(links).toContain('Retake front');
    expect(links).toContain('Retake back');
  });

  it('renders Arabic values right-to-left', () => {
    load(cinDocument());
    expect(fixture.nativeElement.querySelector('#f-last_name').getAttribute('dir')).toBe('rtl');
    expect(fixture.nativeElement.querySelector('#f-document_number').getAttribute('dir')).toBeNull();
  });

  it('sends the user to take a photo when nothing was uploaded yet', () => {
    httpMock.expectOne(DOC_URL).flush('No document uploaded for this session yet', { status: 404, statusText: 'Not Found' });
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'capture'], { replaceUrl: true });
  });

  it('flags and marks an individual field read with low confidence', () => {
    load(cinDocument({
      warnings: ['LOW_CONFIDENCE'],
      fieldConfidence: { ...cinDocument().fieldConfidence, first_name: 0.42 }
    }));

    expect(component.flagFor('first_name')).toContain('hard to read');
    expect(component.flagFor('last_name')).toBeNull();
    expect(component.fieldLevel('first_name')).toEqual({ level: 'low', pct: 42 });
    expect(component.fieldLevel('last_name')?.level).toBe('high');

    const dots = fixture.nativeElement.querySelectorAll('.dot.low');
    expect(dots.length).toBe(1);
    expect(dots[0].textContent).toContain('42% confidence');
  });

  it('shows no confidence dot when the backend sent none for a field', () => {
    load(cinDocument({ fieldConfidence: {} }));
    expect(component.fieldLevel('first_name')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.dot').length).toBe(0);
  });
});
