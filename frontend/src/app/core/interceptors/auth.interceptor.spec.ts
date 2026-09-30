import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('attaches the bearer token to API calls only', () => {
    localStorage.setItem('onboarding_token', 'abc');

    http.get('/api/onboarding/sessions').subscribe();
    http.get('https://example.com/other').subscribe();

    expect(httpMock.expectOne('/api/onboarding/sessions').request.headers.get('Authorization')).toBe('Bearer abc');
    expect(httpMock.expectOne('https://example.com/other').request.headers.has('Authorization')).toBeFalse();
  });

  it('signs out and redirects to login when the token is rejected', () => {
    localStorage.setItem('onboarding_token', 'expired');
    const auth = TestBed.inject(AuthService);
    auth.isAuthenticated.set(true);

    http.get('/api/onboarding/sessions').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/onboarding/sessions').flush('', { status: 401, statusText: 'Unauthorized' });

    expect(auth.isAuthenticated()).toBeFalse();
    expect(localStorage.getItem('onboarding_token')).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], jasmine.objectContaining({}));
  });

  it('does not sign out on 403: signed in, just not allowed', () => {
    localStorage.setItem('onboarding_token', 'abc');
    const auth = TestBed.inject(AuthService);
    auth.isAuthenticated.set(true);

    http.get('/api/admin/reviews').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/admin/reviews').flush('', { status: 403, statusText: 'Forbidden' });

    expect(auth.isAuthenticated()).toBeTrue();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('leaves failed logins to the login page', () => {
    localStorage.setItem('onboarding_token', 'abc');

    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    httpMock.expectOne('/api/auth/login').flush('Invalid email or password', { status: 401, statusText: 'Unauthorized' });

    expect(router.navigate).not.toHaveBeenCalled();
    expect(localStorage.getItem('onboarding_token')).toBe('abc');
  });
});
