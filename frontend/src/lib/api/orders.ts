import { apiFetch } from './client';
import type { CreateOrderItem, Order } from '$lib/types/order';

export function createOrder(items: CreateOrderItem[]): Promise<Order> {
	return apiFetch<Order>('/orders/createOrder', { method: 'POST', body: { items } });
}

export function listMyOrders(): Promise<Order[]> {
	return apiFetch<Order[]>('/orders/user');
}

export function getOrder(orderId: string): Promise<Order> {
	return apiFetch<Order>(`/orders/${orderId}`);
}

export function shipOrder(orderId: string): Promise<Order> {
	return apiFetch<Order>(`/orders/${orderId}/ship`, { method: 'POST' });
}

export function confirmDelivery(orderId: string): Promise<Order> {
	return apiFetch<Order>(`/orders/${orderId}/deliver`, { method: 'POST' });
}

export function cancelOrder(orderId: string): Promise<Order> {
	return apiFetch<Order>(`/orders/${orderId}/cancel`, { method: 'POST' });
}

export function listFulfillmentQueue(): Promise<Order[]> {
	return apiFetch<Order[]>('/orders/fulfillment');
}

export function listRefundFailedQueue(): Promise<Order[]> {
	return apiFetch<Order[]>('/orders/refunds/failed');
}

export function retryRefund(orderId: string): Promise<Order> {
	return apiFetch<Order>(`/orders/${orderId}/retry-refund`, { method: 'POST' });
}
