import { writable } from 'svelte/store';

// Surfaces the last Gateway-proxied response's tracing identifiers (see
// docs/architecture/observability.md) so the UI can show a "Request
// Details" panel without a tracing backend. MessageId has no HTTP
// equivalent -- it only exists on AMQP messages -- so it is intentionally
// not part of this shape.
export interface RequestTrace {
	correlationId: string | null;
	requestId: string | null;
	method: string;
	path: string;
	status: number;
}

export const lastRequest = writable<RequestTrace | null>(null);
