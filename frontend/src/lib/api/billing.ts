import { apiFetch } from './client';
import type { Payment } from '$lib/types/payment';

// Surfaces billing-service's payment step in the UI so the order lifecycle
// (PROCESSING -> INVENTORY_RESERVED -> PAYMENT_APPROVED/FAILED, see
// docs/sequence-diagram.md) is actually observable end to end -- nothing
// in the backend triggers this automatically, a client has to call it.
export function processPayment(orderId: string): Promise<Payment> {
	return apiFetch<Payment>('/billing/api/payments/process', {
		method: 'POST',
		query: { orderId }
	});
}
