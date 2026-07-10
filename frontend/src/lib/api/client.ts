import { get } from 'svelte/store';
import { auth } from '$lib/stores/auth';
import { lastRequest } from '$lib/stores/requestTrace';

// The Gateway is the only backend address this app is allowed to know
// about (see docs/architecture/overview.md and ADR-0025) -- no module in
// lib/api ever talks to a service's own port.
const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
	constructor(
		public status: number,
		message: string
	) {
		super(message);
		this.name = 'ApiError';
	}
}

interface RequestOptions {
	method?: string;
	body?: unknown;
	query?: Record<string, string | number | undefined>;
	/** Most endpoints need Authorization -- the backend derives identity
	 * exclusively from the JWT principal, never from a client-supplied
	 * header. Set to false only for the unauthenticated /auth/login call. */
	authenticated?: boolean;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
	const url = new URL(path, GATEWAY_URL);
	if (query) {
		for (const [key, value] of Object.entries(query)) {
			if (value !== undefined) url.searchParams.set(key, String(value));
		}
	}
	return url.toString();
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
	const { method = 'GET', body, query, authenticated = true } = options;
	const session = get(auth);

	const headers: Record<string, string> = { 'Content-Type': 'application/json' };
	if (authenticated && session) {
		headers['Authorization'] = `Bearer ${session.token}`;
	}

	const url = buildUrl(path, query);
	const response = await fetch(url, {
		method,
		headers,
		body: body !== undefined ? JSON.stringify(body) : undefined
	});

	lastRequest.set({
		correlationId: response.headers.get('X-Correlation-Id'),
		requestId: response.headers.get('X-Request-Id'),
		method,
		path,
		status: response.status
	});

	if (!response.ok) {
		const message = await response.text().catch(() => '');
		throw new ApiError(response.status, message || `${method} ${path} failed with ${response.status}`);
	}

	if (response.status === 204) return undefined as T;
	return (await response.json()) as T;
}
