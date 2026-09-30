import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/guards/auth.guard';

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
        // Separate route (not a query param) so Angular builds a fresh component after the front.
        path: 'capture-back',
        data: { side: 'BACK' },
        title: 'Back of your ID card · Houwiya',
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
  {
    path: 'admin',
    canActivate: [adminGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Review queue · Houwiya',
        loadComponent: () => import('./features/admin/review-queue/review-queue.component').then((m) => m.ReviewQueueComponent)
      },
      {
        path: 'reviews/:documentId',
        title: 'Review · Houwiya',
        loadComponent: () => import('./features/admin/review-detail/review-detail.component').then((m) => m.ReviewDetailComponent)
      },
      {
        path: 'audit',
        title: 'Audit trail · Houwiya',
        loadComponent: () => import('./features/admin/audit-log/audit-log.component').then((m) => m.AuditLogComponent)
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
