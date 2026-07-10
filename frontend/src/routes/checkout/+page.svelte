<script lang="ts">
	import { page } from '$app/stores';
	import { listProducts } from '$lib/api/products';
	import { createOrder } from '$lib/api/orders';
	import { ApiError } from '$lib/api/client';
	import type { Product } from '$lib/types/product';
	import { formatCurrency } from '$lib/utils/format';

	let products = $state<Product[]>([]);
	let selectedProductId = $state($page.url.searchParams.get('productId') ?? '');
	let quantity = $state(1);
	let error = $state<string | null>(null);
	let submitting = $state(false);
	let createdOrderId = $state<string | null>(null);

	const selectedProduct = $derived(products.find((p) => p.id === selectedProductId) ?? null);

	listProducts().then((result) => {
		products = result;
		if (!selectedProductId && result.length > 0) selectedProductId = result[0].id;
	});

	async function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		error = null;
		createdOrderId = null;

		const product = products.find((p) => p.id === selectedProductId);
		if (!product) {
			error = 'Select a product first.';
			return;
		}

		submitting = true;
		try {
			const order = await createOrder([
				{ productId: product.id, quantity, unitPrice: product.price }
			]);
			createdOrderId = order.id;
		} catch (err) {
			error = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			submitting = false;
		}
	}
</script>

<h1>Checkout</h1>

<div class="card">
	<form onsubmit={onSubmit}>
		<label>
			Product
			<select bind:value={selectedProductId}>
				{#each products as product (product.id)}
					<option value={product.id}>
						{product.name} &middot; {formatCurrency(product.price)} &middot; by {product.seller.name}
					</option>
				{/each}
			</select>
		</label>

		{#if selectedProduct}
			<div class="preview">
				<span class="name">{selectedProduct.name}</span>
				<span class="text-muted">by {selectedProduct.seller.name}</span>
				<span class="price">{formatCurrency(selectedProduct.price)}</span>
			</div>
		{/if}

		<label>
			Quantity
			<input type="number" min="1" bind:value={quantity} required />
		</label>
		<button type="submit" disabled={submitting || products.length === 0}>
			{submitting ? 'Placing order...' : 'Place order'}
		</button>
		{#if error}
			<p class="error-text">{error}</p>
		{/if}
	</form>
</div>

{#if createdOrderId}
	<p class="success-text">
		Order created: <strong>{createdOrderId}</strong>
		&mdash; <a href="/orders">view in My Orders</a>
	</p>
{/if}

<style>
	.card {
		max-width: 420px;
	}
	form {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}
	label {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}
	.preview {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
		padding: 0.6rem 0.75rem;
		background: var(--color-bg);
		border-radius: var(--radius-sm);
		font-size: 0.85rem;
	}
	.preview .name {
		font-weight: 600;
	}
	.preview .price {
		margin-left: auto;
		font-weight: 600;
	}
</style>
