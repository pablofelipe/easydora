// Mirrors auth-service's LoginResponse (see auth-service's AuthController).
export interface LoginResponse {
	token: string;
	type: string;
	userId: number;
	email: string;
	firstName: string;
	lastName: string;
	role: 'BUYER' | 'SELLER' | 'ADMIN';
	expiresAt: string;
}

export interface AuthSession {
	token: string;
	userId: number;
	email: string;
	firstName: string;
	lastName: string;
	role: 'BUYER' | 'SELLER' | 'ADMIN';
}
