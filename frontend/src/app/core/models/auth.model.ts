export interface AuthResponse {
  token: string;
  email: string;
  role: 'USER' | 'ADMIN';
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}
