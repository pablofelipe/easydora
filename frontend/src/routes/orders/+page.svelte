<script lang="ts">
	import { listMyOrders } from '$lib/api/orders';
	import type { Order } from '$lib/types/order';
	import { formatDateTime } from '$lib/utils/format';
	import StatusBadge from '$lib/components/StatusBadge.svelte';

	let orders = $state<Order[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);

	listMyOrders()
		.then((result) => (orders = result))
		.catch(() => (error = 'Could not load your orders from the Gateway.'))
		.finally(() => (loading = false));
</script>

<h1>My Orders</h1>

{#if loading}
	<p class="text-muted">Loading...</p>
{:else if error}
	<p class="error-text">{error}</p>
{:else if orders.length === 0}
	<p class="text-muted">No orders yet. <a href="/checkout">Place one</a>.</p>
{:else}
	<div class="card table-card">
		<table>
			<thead>
				<tr>
					<th>Order ID</th>
					<th>Status</th>
					<th>Created</th>
				</tr>
			</thead>
			<tbody>
				{#each orders as order (order.id)}
					<tr>
						<td><a href={`/orders/${order.id}`}><code>{order.id.slice(0, 8)}&hellip;</code></a></td>
						<td><StatusBadge state={order.state} /></td>
						<td class="text-muted">{formatDateTime(order.createdAt)}</td>
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
