<script lang="ts">
	import { listRefundFailedQueue, retryRefund } from '$lib/api/orders';
	import { auth } from '$lib/stores/auth';
	import { ApiError } from '$lib/api/client';
	import type { Order } from '$lib/types/order';
	import { formatCurrency, formatDateTime } from '$lib/utils/format';
	import StatusBadge from '$lib/components/StatusBadge.svelte';

	let orders = $state<Order[]>([]);
	let error = $state<string | null>(null);
	let loading = $state(true);
	let retryingId = $state<string | null>(null);

	function load() {
		loading = true;
		error = null;
		listRefundFailedQueue()
			.then((result) => (orders = result))
			.catch(() => (error = 'Could not load the refund remediation queue from the Gateway.'))
			.finally(() => (loading = false));
	}

	if ($auth?.role === 'ADMIN') {
		load();
	} else {
		loading = false;
	}

	async function onRetry(orderId: string) {
		retryingId = orderId;
		try {
			await retryRefund(orderId);
			orders = orders.filter((order) => order.id !== orderId);
		} catch (err) {
			error = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			retryingId = null;
		}
	}
</script>

<h1>Refund remediation</h1>
<p class="text-muted">
	Orders stuck in REFUND_FAILED (ADR-0034) -- a payment compensation that could not be confirmed,
	needing manual review. Retrying re-sends the same refund request Billing already knows how to
	handle idempotently.
</p>

{#if $auth?.role !== 'ADMIN'}
	<p class="error-text">This page is only available to platform-operations accounts.</p>
{:else if loading}
	<p class="text-muted">Loading...</p>
{:else if error}
	<p class="error-text">{error}</p>
{:else if orders.length === 0}
	<p class="text-muted">No orders currently need refund remediation.</p>
{:else}
	<div class="card table-card">
		<table>
			<thead>
				<tr>
					<th>Order ID</th>
					<th>Status</th>
					<th>Total</th>
					<th>Reason</th>
					<th>Updated</th>
					<th></th>
				</tr>
			</thead>
			<tbody>
				{#each orders as order (order.id)}
					<tr>
						<td><a href={`/orders/${order.id}`}><code>{order.id.slice(0, 8)}&hellip;</code></a></td>
						<td><StatusBadge state={order.state} /></td>
						<td>{formatCurrency(order.totalAmount)}</td>
						<td class="reason">{order.refundFailureReason ?? '—'}</td>
						<td class="text-muted">{formatDateTime(order.updatedAt)}</td>
						<td>
							<button onclick={() => onRetry(order.id)} disabled={retryingId === order.id}>
								{retryingId === order.id ? 'Retrying...' : 'Retry refund'}
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
	.reason {
		max-width: 320px;
		white-space: normal;
		font-size: 0.85rem;
	}
</style>
