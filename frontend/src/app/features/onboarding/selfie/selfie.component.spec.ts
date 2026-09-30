import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { DocumentResponse } from '../../../core/models/onboarding.model';
import { SelfieComponent } from './selfie.component';

const SESSION = 's-1';
const DOCUMENT_URL = `/api/onboarding/sessions/${SESSION}/document`;
const SELFIE_URL = `/api/onboarding/sessions/${SESSION}/selfie`;

function doc(overrides: Partial<DocumentResponse> = {}): DocumentResponse {
  return {
    id: 'd-1', sessionId: SESSION, sessionStatus: 'PENDING_REVIEW', documentType: 'PASSPORT',
    fields: {}, fieldConfidence: {}, documentNumber: null, dateOfBirth: null, expiryDate: null,
    ocrConfidence: 0.9, checksumValid: true, userCorrected: false,
    backSideRequired: false, backSideCaptured: false,
    selfieRequired: true, selfieCaptured: false, faceMatched: null, livenessPassed: null,
    warnings: [], reviewStatus: 'PENDING', decisionReason: null, confirmedAt: null, createdAt: '',
    ...overrides
  };
}

describe('SelfieComponent', () => {
  let fixture: ComponentFixture<SelfieComponent>;
  let component: SelfieComponent;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SelfieComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: SESSION }) } } }
      ]
    });
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(SelfieComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  /** Skips the camera: pretends the three frames were taken and uploads them. */
  function uploadFrames(): void {
    const internals = component as unknown as { frames: Blob[]; upload(): void };
    internals.frames = [new Blob(['a']), new Blob(['b']), new Blob(['c'])];
    internals.upload();
    fixture.detectChanges();
  }

  it('explains the three poses before starting', () => {
    httpMock.expectOne(DOCUMENT_URL).flush(doc());
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Look straight at the camera');
    expect(text).toContain('turn your head to one side');
    expect(text).toContain('to the other side');
  });

  it('sends the user back for the CIN back first', () => {
    httpMock.expectOne(DOCUMENT_URL).flush(doc({ documentType: 'CIN', backSideRequired: true }));
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'capture-back'], { replaceUrl: true });
  });

  it('goes to the photo step when there is no document yet', () => {
    httpMock.expectOne(DOCUMENT_URL).flush('No document', { status: 404, statusText: 'Not Found' });
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'capture'], { replaceUrl: true });
  });

  it('uploads three frames and moves on to review when it matches', () => {
    httpMock.expectOne(DOCUMENT_URL).flush(doc());
    uploadFrames();
    const req = httpMock.expectOne(SELFIE_URL);
    expect((req.request.body as FormData).getAll('frames').length).toBe(3);
    req.flush(doc({ selfieCaptured: true, faceMatched: true, livenessPassed: true }));
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'review']);
  });

  it('offers a retry or continuing to a human reviewer on a mismatch', () => {
    httpMock.expectOne(DOCUMENT_URL).flush(doc());
    uploadFrames();
    httpMock.expectOne(SELFIE_URL).flush(doc({ selfieCaptured: true, faceMatched: false, livenessPassed: true }));
    fixture.detectChanges();

    expect(component.mode()).toBe('failed');
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain("couldn't match your selfie");
    expect(text).toContain('Try again');
    expect(text).not.toMatch(/0\.\d/); // never a score
    component.continueToReview();
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'review']);
  });

  it('explains a failed liveness check', () => {
    httpMock.expectOne(DOCUMENT_URL).flush(doc());
    uploadFrames();
    httpMock.expectOne(SELFIE_URL).flush(doc({ selfieCaptured: true, faceMatched: true, livenessPassed: false }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('taken live');
  });

  it('lets the user retry when the face service is down', () => {
    httpMock.expectOne(DOCUMENT_URL).flush(doc());
    uploadFrames();
    httpMock.expectOne(SELFIE_URL).flush('down', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(component.mode()).toBe('intro');
    expect(fixture.nativeElement.textContent).toContain('temporarily unavailable');
  });
});
