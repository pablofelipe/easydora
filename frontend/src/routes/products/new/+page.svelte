<script lang="ts">
	import { goto } from '$app/navigation';
	import { createProduct } from '$lib/api/products';
	import { ApiError } from '$lib/api/client';

	let name = $state('');
	let description = $state('');
	let price = $state<number | null>(null);
	let initialStock = $state<number | null>(null);
	let error = $state<string | null>(null);
	let submitting = $state(false);

	async function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		error = null;

		if (price === null || initialStock === null) {
			error = 'Fill in price and initial stock.';
			return;
		}

		submitting = true;
		try {
			const product = await createProduct({ name, description, price, initialStock });
			goto(`/products/${product.id}`);
		} catch (err) {
			error = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			submitting = false;
		}
	}
</script>

<h1>New product</h1>

<div class="card">
	<form onsubmit={onSubmit}>
		<label>
			Name
			<input type="text" bind:value={name} maxlength="100" required />
		</label>
		<label>
			Description
			<textarea bind:value={description} maxlength="1000" rows="4"></textarea>
		</label>
		<label>
			Price
			<input type="number" min="0.01" step="0.01" bind:value={price} required />
		</label>
		<label>
			Initial stock
			<input type="number" min="0" step="1" bind:value={initialStock} required />
		</label>
		<button type="submit" disabled={submitting}>
			{submitting ? 'Creating product...' : 'Create product'}
		</button>
		{#if error}
			<p class="error-text">{error}</p>
		{/if}
	</form>
</div>

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
	textarea {
		resize: vertical;
		padding: 0.5rem 0.75rem;
		font-family: inherit;
		font-size: 0.9rem;
		color: var(--color-text);
		background: var(--color-surface);
		border: 1px solid var(--color-border);
		border-radius: var(--radius-sm);
	}
</style>
