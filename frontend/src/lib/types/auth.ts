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

// Mirrors auth-service's SignupRequest -- role is restricted to BUYER/SELLER
// here since ADMIN is never offered as a self-signup option (auth-service
// itself defaults SignupRequest.role to BUYER, ADMIN accounts are seeded,
// not signed up).
export interface SignupRequest {
	email: string;
	password: string;
	firstName: string;
	lastName: string;
	role: 'BUYER' | 'SELLER';
}

// Mirrors auth-service's SignupResponse. verificationToken/verificationUrl
// exist because this project has no real email delivery -- the token is
// handed back directly instead of being emailed.
export interface SignupResponse {
	id: number;
	email: string;
	firstName: string;
	lastName: string;
	role: string;
	status: string;
	createdAt: string;
	verificationToken: string;
	verificationUrl: string;
}
