<script lang="ts">
	import { formatCurrency, formatDateTime } from '$lib/utils/format';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();
	const product = $derived(data.product);
</script>

<a href="/products">&larr; Back to products</a>

<h1>{product.name}</h1>
<p>{product.description}</p>
<p class="price">{formatCurrency(product.price)}</p>
<p class="meta">Sold by {product.seller.name} &middot; listed {formatDateTime(product.createdAt)}</p>

<a class="button" href={`/checkout?productId=${product.id}`}>Buy this product</a>

<style>
	.price {
		font-size: 1.25rem;
		font-weight: 700;
	}
	.meta {
		color: #777;
		font-size: 0.85rem;
	}
	.button {
		display: inline-block;
		margin-top: 1rem;
		padding: 0.5rem 1rem;
		background: #333;
		color: #fff;
		text-decoration: none;
		border-radius: 4px;
	}
</style>
