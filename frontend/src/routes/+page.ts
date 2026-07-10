import { redirect } from '@sveltejs/kit';
import { get } from 'svelte/store';
import { auth } from '$lib/stores/auth';

export function load() {
	throw redirect(307, get(auth) ? '/products' : '/login');
}
