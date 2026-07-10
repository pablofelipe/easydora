import { apiFetch } from './client';
import type { Notification } from '$lib/types/notification';

export function getNotifications(orderId: string): Promise<Notification[]> {
	return apiFetch<Notification[]>(`/notification/notifications/${orderId}`);
}
