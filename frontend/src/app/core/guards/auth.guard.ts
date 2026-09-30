import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.isAuthenticated()) {
    return true;
  }
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** Admin area. The backend enforces this too (403); the guard just avoids a dead-end page. */
export const adminGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (!auth.isAuthenticated()) {
    return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  return auth.role() === 'ADMIN' ? true : inject(Router).createUrlTree(['/']);
};

/** Keeps signed-in users off the login/register pages. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;
};
