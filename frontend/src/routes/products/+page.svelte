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
	<p>Loading...</p>
{:else if error}
	<p class="error">{error}</p>
{:else if products.length === 0}
	<p>No products available yet.</p>
{:else}
	<ul class="grid">
		{#each products as product (product.id)}
			<li>
				<a href={`/products/${product.id}`}>
					<h3>{product.name}</h3>
					<p>{formatCurrency(product.price)}</p>
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
		grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
		gap: 1rem;
		padding: 0;
	}
	.grid li {
		border: 1px solid #ddd;
		border-radius: 4px;
		padding: 1rem;
	}
	.grid a {
		text-decoration: none;
		color: inherit;
	}
	.seller {
		color: #777;
		font-size: 0.85rem;
	}
	.error {
		color: #b00020;
	}
</style>
