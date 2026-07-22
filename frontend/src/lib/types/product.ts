// Mirrors products-service's ProductResponse.
export interface Seller {
	userId: string;
	name: string;
	avatarUrl: string | null;
}

export interface Product {
	id: string;
	name: string;
	description: string;
	price: number;
	seller: Seller;
	active: boolean;
	createdAt: string;
	updatedAt: string | null;
}

// Mirrors products-service's ProductRequest.
export interface CreateProductRequest {
	name: string;
	description: string;
	price: number;
	initialStock: number;
}
