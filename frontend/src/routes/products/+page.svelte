<script lang="ts">
	import { listProducts } from '$lib/api/products';
	import type { Product } from '$lib/types/product';
	import { formatCurrency } from '$lib/utils/format';

	let products = $state<Product[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);

	listProducts()
		.then((result) => (products = result))
		.catch(() => (error = 'Could not load products from the Gateway.'))
		.finally(() => (loading = false));
</script>

<h1>Products</h1>

{#if loading}
	<p class="text-muted">Loading...</p>
{:else if error}
	<p class="error-text">{error}</p>
{:else if products.length === 0}
	<p class="text-muted">No products available yet.</p>
{:else}
	<ul class="grid">
		{#each products as product (product.id)}
			<li>
				<a class="card" href={`/products/${product.id}`}>
					<h3>{product.name}</h3>
					<p class="price">{formatCurrency(product.price)}</p>
					<p class="seller">by {product.seller.name}</p>
				</a>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.grid {
		list-style: none;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
		gap: 1rem;
		padding: 0;
		margin: 0;
	}
	.grid a {
		display: block;
		text-decoration: none;
		color: inherit;
		transition: box-shadow 0.15s ease, transform 0.15s ease;
	}
	.grid a:hover {
		box-shadow: var(--shadow-md);
		transform: translateY(-1px);
		text-decoration: none;
	}
	.grid h3 {
		margin: 0 0 0.5rem;
		font-size: 1rem;
	}
	.price {
		margin: 0;
		font-weight: 600;
	}
	.seller {
		margin: 0.35rem 0 0;
		color: var(--color-text-muted);
		font-size: 0.85rem;
	}
</style>
