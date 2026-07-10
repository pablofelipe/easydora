// Mirrors billing-service's PaymentDTO.
export interface Payment {
	id: number;
	orderId: string;
	userId: number | null;
	amount: number;
	status: 'PENDING' | 'APPROVED' | 'FAILED';
	transactionId: string;
	failureReason: string | null;
	createdAt: string;
	processedAt: string | null;
}
