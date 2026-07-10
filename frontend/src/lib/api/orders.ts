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
