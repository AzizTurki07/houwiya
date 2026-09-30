import { ComponentFixture, TestBed, fakeAsync, flush } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { CaptureComponent } from './capture.component';

const SESSION = 's-1';
const SESSION_URL = `/api/onboarding/sessions/${SESSION}`;
const UPLOAD_URL = `${SESSION_URL}/document`;

describe('CaptureComponent', () => {
  let fixture: ComponentFixture<CaptureComponent>;
  let component: CaptureComponent;
  let httpMock: HttpTestingController;
  let router: Router;

  function setup(queryParams: Record<string, string> = {}, data: Record<string, string> = {}): void {
    TestBed.configureTestingModule({
      imports: [CaptureComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ id: SESSION }), queryParamMap: convertToParamMap(queryParams), data }
          }
        }
      ]
    });
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(CaptureComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function sessionLoaded(status = 'CONSENT_GIVEN'): void {
    httpMock.expectOne(SESSION_URL).flush({ id: SESSION, status, consentGiven: true, createdAt: '', updatedAt: '' });
    fixture.detectChanges();
  }

  function pickPhoto(): void {
    const file = new File([new Uint8Array([1, 2, 3])], 'id.jpg', { type: 'image/jpeg' });
    component.onFileSelected({ target: { files: [file], value: 'id.jpg' } } as unknown as Event);
    fixture.detectChanges();
  }

  afterEach(() => httpMock.verify());

  it('preselects the document type from the retake link', () => {
    setup({ type: 'CIN' });
    sessionLoaded();
    expect(component.documentType()).toBe('CIN');
    expect(fixture.nativeElement.textContent).toContain('plain, dark surface');
  });

  it('sends users who have not consented back to the consent step', () => {
    setup();
    sessionLoaded('STARTED');
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'consent'], { replaceUrl: true });
  });

  it('rejects non-image files', () => {
    setup();
    sessionLoaded();
    const file = new File(['%PDF'], 'id.pdf', { type: 'application/pdf' });
    component.onFileSelected({ target: { files: [file], value: 'id.pdf' } } as unknown as Event);
    expect(component.mode()).toBe('choose');
    expect(component.error()?.message).toContain('image file');
  });

  it('uploads the photo with its document type and opens the review screen', fakeAsync(() => {
    setup();
    sessionLoaded();
    component.selectType('PASSPORT');
    pickPhoto();
    expect(component.mode()).toBe('preview');

    component.submit();
    flush();

    const req = httpMock.expectOne(UPLOAD_URL);
    const body = req.request.body as FormData;
    expect(body.get('documentType')).toBe('PASSPORT');
    expect(body.get('side')).toBe('FRONT');
    expect(body.get('file') instanceof Blob).toBeTrue();
    req.flush({ documentType: 'PASSPORT', backSideRequired: false, backSideCaptured: false });

    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'review']);
  }));

  it('asks for the back of the card after a CIN front', fakeAsync(() => {
    setup();
    sessionLoaded();
    component.selectType('CIN');
    pickPhoto();

    component.submit();
    flush();
    httpMock.expectOne(UPLOAD_URL).flush({ documentType: 'CIN', backSideRequired: true, backSideCaptured: false });

    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'capture-back']);
  }));

  it('goes straight to review after retaking a CIN front whose back is already in', fakeAsync(() => {
    setup();
    sessionLoaded('PENDING_REVIEW');
    component.selectType('CIN');
    pickPhoto();

    component.submit();
    flush();
    httpMock.expectOne(UPLOAD_URL).flush({ documentType: 'CIN', backSideRequired: true, backSideCaptured: true });

    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'review']);
  }));

  it('photographs the back of a CIN on the capture-back route', fakeAsync(() => {
    setup({}, { side: 'BACK' });
    sessionLoaded('PENDING_REVIEW');
    expect(component.side).toBe('BACK');
    expect(component.documentType()).toBe('CIN');
    expect(fixture.nativeElement.textContent).toContain('Now the back of your ID card');
    expect(fixture.nativeElement.querySelector('[role=radiogroup]')).toBeNull();

    pickPhoto();
    component.submit();
    flush();
    const req = httpMock.expectOne(UPLOAD_URL);
    expect((req.request.body as FormData).get('side')).toBe('BACK');
    expect((req.request.body as FormData).get('documentType')).toBe('CIN');
    req.flush({ documentType: 'CIN', backSideRequired: true, backSideCaptured: true });

    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'review']);
  }));

  it('sends the user to the front first if they open the back step too early', () => {
    setup({}, { side: 'BACK' });
    sessionLoaded('CONSENT_GIVEN');
    expect(router.navigate).toHaveBeenCalledWith(['/onboarding', SESSION, 'capture'], { replaceUrl: true });
  });

  it('asks for a retake when the document could not be read (422)', fakeAsync(() => {
    setup();
    sessionLoaded();
    component.selectType('PASSPORT');
    pickPhoto();

    component.submit();
    flush();
    httpMock.expectOne(UPLOAD_URL).flush('Could not locate a machine-readable zone.', { status: 422, statusText: 'Unprocessable Entity' });
    fixture.detectChanges();

    expect(component.mode()).toBe('preview');
    expect(component.error()?.kind).toBe('unreadable');
    expect(fixture.nativeElement.textContent).toContain("We couldn't read your document");
    expect(fixture.nativeElement.textContent).toContain('machine-readable zone');
  }));

  it('offers to retry the same photo when the AI service is down (502)', fakeAsync(() => {
    setup();
    sessionLoaded();
    component.selectType('CIN');
    pickPhoto();

    component.submit();
    flush();
    httpMock.expectOne(UPLOAD_URL).flush('Document extraction is temporarily unavailable.', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(component.error()?.kind).toBe('unavailable');
    expect(component.image()).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Try again');
  }));
});
