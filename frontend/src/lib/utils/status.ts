import type { OrderState } from '$lib/types/order';

const BADGE_BY_STATE: Record<OrderState, string> = {
	PENDING: 'badge-gray',
	PROCESSING: 'badge-blue',
	INVENTORY_RESERVED: 'badge-purple',
	INVENTORY_FAILED: 'badge-red',
	PAYMENT_APPROVED: 'badge-green',
	PAYMENT_FAILED: 'badge-red',
	SHIPPED: 'badge-blue',
	DELIVERED: 'badge-green',
	CANCELLED: 'badge-gray',
	// ADR-0034: REFUNDING/REFUNDED are system-driven, no user action
	// triggers or resolves them. REFUND_FAILED can be manually resolved by
	// an ADMIN account via the /refunds remediation queue.
	REFUNDING: 'badge-purple',
	REFUNDED: 'badge-gray',
	REFUND_FAILED: 'badge-red'
};

export function badgeClassFor(state: OrderState): string {
	return BADGE_BY_STATE[state] ?? 'badge-gray';
}
