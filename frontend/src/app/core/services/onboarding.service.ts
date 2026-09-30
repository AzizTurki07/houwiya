import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  DocumentResponse,
  DocumentSide,
  DocumentType,
  SessionResponse
} from '../models/onboarding.model';

@Injectable({ providedIn: 'root' })
export class OnboardingService {
  private readonly base = `${environment.apiUrl}/onboarding/sessions`;

  constructor(private http: HttpClient) {}

  createSession(): Observable<SessionResponse> {
    return this.http.post<SessionResponse>(this.base, null);
  }

  listSessions(): Observable<SessionResponse[]> {
    return this.http.get<SessionResponse[]>(this.base);
  }

  getSession(id: string): Observable<SessionResponse> {
    return this.http.get<SessionResponse>(`${this.base}/${id}`);
  }

  /** Deletes the verification with its document and photos. */
  deleteSession(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  giveConsent(id: string): Observable<SessionResponse> {
    return this.http.post<SessionResponse>(`${this.base}/${id}/consent`, null);
  }

  /** Emits upload-progress events, then the extraction result as an HttpResponse. */
  uploadDocument(
    id: string,
    documentType: DocumentType,
    image: Blob,
    filename: string,
    side: DocumentSide = 'FRONT'
  ): Observable<HttpEvent<DocumentResponse>> {
    const form = new FormData();
    form.append('documentType', documentType);
    form.append('side', side);
    form.append('file', image, filename);
    return this.http.post<DocumentResponse>(`${this.base}/${id}/document`, form, {
      reportProgress: true,
      observe: 'events'
    });
  }

  /** Three frames: looking straight, head turned one way, then the other. */
  submitSelfie(id: string, frames: Blob[]): Observable<DocumentResponse> {
    const form = new FormData();
    frames.forEach((frame, i) => form.append('frames', frame, `selfie-${i}.jpg`));
    return this.http.post<DocumentResponse>(`${this.base}/${id}/selfie`, form);
  }

  getDocument(id: string): Observable<DocumentResponse> {
    return this.http.get<DocumentResponse>(`${this.base}/${id}/document`);
  }

  confirmDocument(id: string, fields: Record<string, string | null>): Observable<DocumentResponse> {
    return this.http.post<DocumentResponse>(`${this.base}/${id}/confirm`, { fields });
  }
}
