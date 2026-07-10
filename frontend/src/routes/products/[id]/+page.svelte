<script lang="ts">
	import { formatCurrency, formatDateTime } from '$lib/utils/format';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();
	const product = $derived(data.product);
</script>

<a href="/products">&larr; Back to products</a>

<div class="card">
	<h1>{product.name}</h1>
	<p>{product.description}</p>
	<p class="price">{formatCurrency(product.price)}</p>
	<p class="text-muted">Sold by {product.seller.name} &middot; listed {formatDateTime(product.createdAt)}</p>

	<a class="btn btn-primary" href={`/checkout?productId=${product.id}`}>Buy this product</a>
</div>

<style>
	.card {
		margin-top: 1rem;
	}
	.price {
		font-size: 1.25rem;
		font-weight: 700;
	}
	.btn {
		margin-top: 0.5rem;
	}
</style>
