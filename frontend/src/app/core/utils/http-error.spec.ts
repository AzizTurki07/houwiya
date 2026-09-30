import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { errorMessage } from './http-error';

describe('errorMessage', () => {
  it('uses the plain-text reason from the backend', () => {
    const err = new HttpErrorResponse({ status: 409, error: 'This document is already registered' });
    expect(errorMessage(err)).toBe('This document is already registered');
  });

  it('uses a JSON detail/message when present', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 422, error: { detail: 'No MRZ' } }))).toBe('No MRZ');
    expect(errorMessage(new HttpErrorResponse({ status: 400, error: { message: 'Bad' } }))).toBe('Bad');
  });

  it('explains rate limiting using Retry-After', () => {
    const withHeader = new HttpErrorResponse({ status: 429, headers: new HttpHeaders({ 'Retry-After': '42' }) });
    expect(errorMessage(withHeader)).toBe('Too many attempts. Please wait 42 seconds and try again.');
    const minutes = new HttpErrorResponse({ status: 429, headers: new HttpHeaders({ 'Retry-After': '600' }) });
    expect(errorMessage(minutes)).toContain('10 minutes');
  });

  it('explains network failures', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain("Can't reach the server");
  });

  it('never shows an HTML error page to the user', () => {
    const err = new HttpErrorResponse({ status: 502, error: '<html><body>Bad Gateway</body></html>' });
    expect(errorMessage(err, 'fallback')).toBe('fallback');
  });

  it('falls back for non-HTTP errors', () => {
    expect(errorMessage(new Error('boom'), 'fallback')).toBe('fallback');
  });
});
