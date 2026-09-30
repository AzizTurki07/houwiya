import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { adminGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';

describe('adminGuard', () => {
  let auth: AuthService;
  let router: Router;

  const run = () =>
    TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, { url: '/admin' } as RouterStateSnapshot)
    );

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => localStorage.clear());

  it('sends signed-out visitors to login', () => {
    auth.isAuthenticated.set(false);
    expect(router.serializeUrl(run() as UrlTree)).toBe('/login?returnUrl=%2Fadmin');
  });

  it('sends applicants back home', () => {
    auth.isAuthenticated.set(true);
    auth.role.set('USER');
    expect(router.serializeUrl(run() as UrlTree)).toBe('/');
  });

  it('lets admins in', () => {
    auth.isAuthenticated.set(true);
    auth.role.set('ADMIN');
    expect(run()).toBeTrue();
  });
});
