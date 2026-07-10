// Mirrors notification-service's persisted notification row
// (GET /notification/notifications/{orderId}).
export interface Notification {
	eventType: string;
	status: string;
	payload: Record<string, unknown>;
	createdAt: string;
}
