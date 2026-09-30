import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Only attach the token for calls to our own API, not third-party requests.
  if (!req.url.startsWith(environment.apiUrl)) {
    return next(req);
  }

  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.getToken();
  const authReq = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authReq).pipe(
    catchError((err: unknown) => {
      // Expired/invalid token (the JWT lasts 24h): drop it and send the user to sign in again.
      // Only 401 means that -- a 403 is "signed in, but not allowed" and must not log out.
      // Login/register failures are 401s too, but those pages show their own error.
      const isAuthCall = req.url.startsWith(`${environment.apiUrl}/auth/`);
      if (err instanceof HttpErrorResponse && err.status === 401 && token && !isAuthCall) {
        auth.logout();
        router.navigate(['/login'], { queryParams: { returnUrl: router.url, expired: 1 } });
      }
      return throwError(() => err);
    })
  );
};
