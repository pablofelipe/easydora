import { apiFetch } from './client';
import type { Product } from '$lib/types/product';

export function listProducts(): Promise<Product[]> {
	return apiFetch<Product[]>('/products/all-products');
}

export function getProduct(id: string): Promise<Product> {
	return apiFetch<Product>(`/products/${id}`);
}
