import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SessionsComponent } from './sessions.component';

describe('SessionsComponent', () => {
  let fixture: ComponentFixture<SessionsComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SessionsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SessionsComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/onboarding/sessions').flush([
      { id: 's-1', status: 'APPROVED', consentGiven: true, createdAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:00Z' },
      { id: 's-2', status: 'PENDING_REVIEW', consentGiven: true, createdAt: '2026-09-30T10:00:00Z', updatedAt: '2026-09-30T10:00:00Z' }
    ]);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  const buttons = (label: string): HTMLButtonElement[] =>
    Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .filter((b) => b.textContent!.trim() === label);

  it('deletes a verification only after a second, explicit click', () => {
    expect(fixture.nativeElement.querySelectorAll('li.entry').length).toBe(2);

    buttons('Delete')[0].click();
    fixture.detectChanges();
    httpMock.expectNone('/api/onboarding/sessions/s-2');
    expect(fixture.nativeElement.textContent).toContain('Delete this verification and all its data?');

    buttons('Delete')[0].click(); // the confirm button now replaces the first one
    httpMock.expectOne({ method: 'DELETE', url: '/api/onboarding/sessions/s-2' }).flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('li.entry').length).toBe(1);
  });

  it('can back out of deleting', () => {
    buttons('Delete')[0].click();
    fixture.detectChanges();
    buttons('Keep')[0].click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Delete this verification and all its data?');
  });
});
