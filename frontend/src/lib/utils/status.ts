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
	CANCELLED: 'badge-gray'
};

export function badgeClassFor(state: OrderState): string {
	return BADGE_BY_STATE[state] ?? 'badge-gray';
}
