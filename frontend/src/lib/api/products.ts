import { apiFetch } from './client';
import type { CreateProductRequest, Product } from '$lib/types/product';

export function listProducts(): Promise<Product[]> {
	return apiFetch<Product[]>('/products/all-products');
}

export function getProduct(id: string): Promise<Product> {
	return apiFetch<Product>(`/products/${id}`);
}

export function createProduct(request: CreateProductRequest): Promise<Product> {
	return apiFetch<Product>('/products/createProduct', { method: 'POST', body: request });
}
