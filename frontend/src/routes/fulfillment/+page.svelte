<script lang="ts">
	import { listFulfillmentQueue, shipOrder } from '$lib/api/orders';
	import { auth } from '$lib/stores/auth';
	import { ApiError } from '$lib/api/client';
	import type { Order } from '$lib/types/order';
	import { formatCurrency, formatDateTime } from '$lib/utils/format';
	import StatusBadge from '$lib/components/StatusBadge.svelte';

	let orders = $state<Order[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);
	let shippingId = $state<string | null>(null);

	function load() {
		loading = true;
		error = null;
		listFulfillmentQueue()
			.then((result) => (orders = result))
			.catch(() => (error = 'Could not load the fulfillment queue from the Gateway.'))
			.finally(() => (loading = false));
	}

	if ($auth?.role === 'ADMIN') {
		load();
	} else {
		loading = false;
	}

	async function onShip(orderId: string) {
		shippingId = orderId;
		try {
			await shipOrder(orderId);
			orders = orders.filter((order) => order.id !== orderId);
		} catch (err) {
			error = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			shippingId = null;
		}
	}
</script>

<h1>Fulfillment queue</h1>
<p class="text-muted">
	Paid orders waiting to be shipped -- a platform-operations action, not tied to any one
	seller (an order can span more than one seller's products).
</p>

{#if $auth?.role !== 'ADMIN'}
	<p class="error-text">This page is only available to platform-operations accounts.</p>
{:else if loading}
	<p class="text-muted">Loading...</p>
{:else if error}
	<p class="error-text">{error}</p>
{:else if orders.length === 0}
	<p class="text-muted">No orders waiting to be shipped.</p>
{:else}
	<div class="card table-card">
		<table>
			<thead>
				<tr>
					<th>Order ID</th>
					<th>Status</th>
					<th>Total</th>
					<th>Created</th>
					<th></th>
				</tr>
			</thead>
			<tbody>
				{#each orders as order (order.id)}
					<tr>
						<td><a href={`/orders/${order.id}`}><code>{order.id.slice(0, 8)}&hellip;</code></a></td>
						<td><StatusBadge state={order.state} /></td>
						<td>{formatCurrency(order.totalAmount)}</td>
						<td class="text-muted">{formatDateTime(order.createdAt)}</td>
						<td>
							<button onclick={() => onShip(order.id)} disabled={shippingId === order.id}>
								{shippingId === order.id ? 'Shipping...' : 'Mark as shipped'}
							</button>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>
{/if}

<style>
	.table-card {
		padding: 0;
		overflow: hidden;
	}
	.table-card table {
		margin: 0;
	}
</style>
