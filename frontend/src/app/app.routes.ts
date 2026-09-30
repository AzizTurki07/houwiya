import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/guards/auth.guard';

// Screens are lazy-loaded so the first paint (login) stays small -- matters on phones (Phase 6).
export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    data: { mode: 'login' },
    title: 'Sign in · Houwiya',
    loadComponent: () => import('./features/auth/auth-page.component').then((m) => m.AuthPageComponent)
  },
  {
    path: 'register',
    canActivate: [guestGuard],
    data: { mode: 'register' },
    title: 'Create account · Houwiya',
    loadComponent: () => import('./features/auth/auth-page.component').then((m) => m.AuthPageComponent)
  },
  {
    path: '',
    pathMatch: 'full',
    canActivate: [authGuard],
    title: 'My verifications · Houwiya',
    loadComponent: () =>
      import('./features/onboarding/sessions/sessions.component').then((m) => m.SessionsComponent)
  },
  {
    path: 'onboarding/:id',
    canActivate: [authGuard],
    children: [
      {
        path: 'consent',
        title: 'Consent · Houwiya',
        loadComponent: () =>
          import('./features/onboarding/consent/consent.component').then((m) => m.ConsentComponent)
      },
      {
        path: 'capture',
        title: 'Photograph your document · Houwiya',
        loadComponent: () =>
          import('./features/onboarding/capture/capture.component').then((m) => m.CaptureComponent)
      },
      {
        path: 'review',
        title: 'Check your details · Houwiya',
        loadComponent: () =>
          import('./features/onboarding/review/review.component').then((m) => m.ReviewComponent)
      },
      {
        path: 'result',
        title: 'Result · Houwiya',
        loadComponent: () =>
          import('./features/onboarding/result/result.component').then((m) => m.ResultComponent)
      },
      { path: '', pathMatch: 'full', redirectTo: 'consent' }
    ]
  },
  { path: '**', redirectTo: '' }
];
