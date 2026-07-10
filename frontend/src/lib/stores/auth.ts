import { writable } from 'svelte/store';
import type { AuthSession } from '$lib/types/auth';

const STORAGE_KEY = 'easydora.session';

function readStoredSession(): AuthSession | null {
	if (typeof localStorage === 'undefined') return null;
	const raw = localStorage.getItem(STORAGE_KEY);
	if (!raw) return null;
	try {
		return JSON.parse(raw) as AuthSession;
	} catch {
		return null;
	}
}

function createAuthStore() {
	const store = writable<AuthSession | null>(readStoredSession());
	const { subscribe, set } = store;

	return {
		subscribe,
		login(session: AuthSession) {
			localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
			set(session);
		},
		logout() {
			localStorage.removeItem(STORAGE_KEY);
			set(null);
		}
	};
}

export const auth = createAuthStore();
