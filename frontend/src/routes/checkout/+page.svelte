<script lang="ts">
	import { page } from '$app/stores';
	import { listProducts } from '$lib/api/products';
	import { createOrder } from '$lib/api/orders';
	import { ApiError } from '$lib/api/client';
	import type { Product } from '$lib/types/product';

	let products = $state<Product[]>([]);
	let selectedProductId = $state($page.url.searchParams.get('productId') ?? '');
	let quantity = $state(1);
	let error = $state<string | null>(null);
	let submitting = $state(false);
	let createdOrderId = $state<string | null>(null);

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

<form onsubmit={onSubmit}>
	<label>
		Product
		<select bind:value={selectedProductId}>
			{#each products as product (product.id)}
				<option value={product.id}>{product.name}</option>
			{/each}
		</select>
	</label>
	<label>
		Quantity
		<input type="number" min="1" bind:value={quantity} required />
	</label>
	<button type="submit" disabled={submitting || products.length === 0}>
		{submitting ? 'Placing order...' : 'Place order'}
	</button>
	{#if error}
		<p class="error">{error}</p>
	{/if}
</form>

{#if createdOrderId}
	<p class="success">
		Order created: <strong>{createdOrderId}</strong>
		&mdash; <a href="/orders">view in My Orders</a>
	</p>
{/if}

<style>
	form {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		max-width: 320px;
	}
	label {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}
	.error {
		color: #b00020;
	}
	.success {
		color: #1a7a1a;
	}
</style>
