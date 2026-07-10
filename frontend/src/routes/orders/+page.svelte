<script lang="ts">
	import { listMyOrders } from '$lib/api/orders';
	import type { Order } from '$lib/types/order';
	import { formatDateTime } from '$lib/utils/format';

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
	<p>Loading...</p>
{:else if error}
	<p class="error">{error}</p>
{:else if orders.length === 0}
	<p>No orders yet. <a href="/checkout">Place one</a>.</p>
{:else}
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
					<td><a href={`/orders/${order.id}`}>{order.id}</a></td>
					<td>{order.state}</td>
					<td>{formatDateTime(order.createdAt)}</td>
				</tr>
			{/each}
		</tbody>
	</table>
{/if}

<style>
	table {
		width: 100%;
		border-collapse: collapse;
	}
	th,
	td {
		text-align: left;
		padding: 0.5rem;
		border-bottom: 1px solid #eee;
	}
	.error {
		color: #b00020;
	}
</style>
