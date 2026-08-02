// Mirrors orders-service's OrderResponse/OrderItemResponse and the state
// machine described in statemachine/OrderStateMachineConfig.java.
export type OrderState =
	| 'PENDING'
	| 'PROCESSING'
	| 'INVENTORY_RESERVED'
	| 'INVENTORY_FAILED'
	| 'PAYMENT_APPROVED'
	| 'PAYMENT_FAILED'
	| 'SHIPPED'
	| 'DELIVERED'
	| 'CANCELLED'
	| 'REFUNDING'
	| 'REFUNDED'
	| 'REFUND_FAILED';

export interface OrderItem {
	id: string;
	productId: string;
	quantity: number;
	unitPrice: number;
	subtotal: number;
}

export interface Order {
	id: string;
	userId: number;
	totalAmount: number;
	state: OrderState;
	items: OrderItem[];
	createdAt: string;
	updatedAt: string;
	refundFailureReason?: string | null;
}

export interface CreateOrderItem {
	productId: string;
	quantity: number;
	unitPrice: number;
}
