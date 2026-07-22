import { apiFetch } from './client';
import type { LoginResponse, SignupRequest, SignupResponse } from '$lib/types/auth';

export function login(email: string, password: string): Promise<LoginResponse> {
	return apiFetch<LoginResponse>('/auth/login', {
		method: 'POST',
		body: { email, password },
		authenticated: false
	});
}

export function signup(request: SignupRequest): Promise<SignupResponse> {
	return apiFetch<SignupResponse>('/auth/signup', {
		method: 'POST',
		body: request,
		authenticated: false
	});
}

export function verifyEmail(token: string): Promise<void> {
	return apiFetch<void>('/auth/verify-email', {
		method: 'GET',
		query: { token },
		authenticated: false
	});
}
