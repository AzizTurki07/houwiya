import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuditEvent, ReviewDecision, ReviewDetail, ReviewQueueItem } from '../models/admin.model';
import { DocumentSide, ReviewStatus } from '../models/onboarding.model';

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly base = `${environment.apiUrl}/admin`;

  constructor(private http: HttpClient) {}

  queue(status: ReviewStatus = 'NEEDS_REVIEW'): Observable<ReviewQueueItem[]> {
    return this.http.get<ReviewQueueItem[]>(`${this.base}/reviews`, { params: new HttpParams().set('status', status) });
  }

  /** Opening a review decrypts the applicant's data and is recorded in the audit trail. */
  open(documentId: string): Observable<ReviewDetail> {
    return this.http.get<ReviewDetail>(`${this.base}/reviews/${documentId}`);
  }

  /** The photo as a Blob: it needs the auth header, so it can't be a plain <img src>. */
  image(documentId: string, side: DocumentSide): Observable<Blob> {
    return this.http.get(`${this.base}/reviews/${documentId}/images/${side}`, { responseType: 'blob' });
  }

  decide(documentId: string, decision: ReviewDecision, reason: string | null): Observable<ReviewDetail> {
    return this.http.post<ReviewDetail>(`${this.base}/reviews/${documentId}/decision`, { decision, reason });
  }

  audit(targetId?: string): Observable<AuditEvent[]> {
    const params = targetId ? new HttpParams().set('targetId', targetId) : new HttpParams().set('limit', 100);
    return this.http.get<AuditEvent[]>(`${this.base}/audit`, { params });
  }
}
