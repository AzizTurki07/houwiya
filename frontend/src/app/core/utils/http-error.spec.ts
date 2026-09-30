import { HttpErrorResponse } from '@angular/common/http';
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
