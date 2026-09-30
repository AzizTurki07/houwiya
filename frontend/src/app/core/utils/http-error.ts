import { HttpErrorResponse } from '@angular/common/http';

/**
 * Turns a backend error into something safe to show the user. The backend's own
 * handlers return a plain-text reason (GlobalExceptionHandler); Spring's default
 * validation errors are JSON; network failures have status 0.
 */
export function errorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (!(err instanceof HttpErrorResponse)) {
    return fallback;
  }
  if (err.status === 0) {
    return "Can't reach the server. Check your connection and try again.";
  }
  if (err.status === 429) {
    const wait = Number(err.headers?.get('Retry-After'));
    return wait > 0
      ? `Too many attempts. Please wait ${wait < 90 ? `${wait} seconds` : `${Math.ceil(wait / 60)} minutes`} and try again.`
      : 'Too many attempts. Please wait a moment and try again.';
  }
  const body = err.error;
  if (typeof body === 'string' && body.trim() && !body.trim().startsWith('<')) {
    return body.trim();
  }
  if (body && typeof body === 'object') {
    const text = body.detail ?? body.message;
    if (typeof text === 'string' && text.trim()) {
      return text.trim();
    }
  }
  if (err.status === 401) {
    return 'Your session has expired. Please sign in again.';
  }
  return fallback;
}
