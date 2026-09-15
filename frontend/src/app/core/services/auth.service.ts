import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthResponse, LoginRequest, RegisterRequest } from '../models/auth.model';

const TOKEN_KEY = 'onboarding_token';
const ROLE_KEY = 'onboarding_role';

@Injectable({ providedIn: 'root' })
export class AuthService {
  // signal so components can reactively show/hide auth-gated UI
  isAuthenticated = signal<boolean>(!!localStorage.getItem(TOKEN_KEY));
  role = signal<string | null>(localStorage.getItem(ROLE_KEY));

  constructor(private http: HttpClient) {}

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/register`, request)
      .pipe(tap((res) => this.storeSession(res)));
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiUrl}/auth/login`, request)
      .pipe(tap((res) => this.storeSession(res)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ROLE_KEY);
    this.isAuthenticated.set(false);
    this.role.set(null);
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  private storeSession(res: AuthResponse): void {
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(ROLE_KEY, res.role);
    this.isAuthenticated.set(true);
    this.role.set(res.role);
  }
}
