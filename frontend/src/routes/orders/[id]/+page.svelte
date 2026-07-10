<script lang="ts">
	import { getOrder } from '$lib/api/orders';
	import { processPayment } from '$lib/api/billing';
	import { getNotifications } from '$lib/api/notifications';
	import { ApiError } from '$lib/api/client';
	import RequestDetails from '$lib/components/RequestDetails.svelte';
	import type { Order } from '$lib/types/order';
	import type { Notification } from '$lib/types/notification';
	import { formatCurrency, formatDateTime } from '$lib/utils/format';
	import type { PageProps } from './$types';

	let { data }: PageProps = $props();
	let order = $state<Order>(data.order);
	// Resets all local state whenever navigation loads a different order (a
	// new `data` prop) without discarding local mutations made in between
	// by onProcessPayment/loadNotifications, neither of which touches
	// `data` itself.
	$effect(() => {
		order = data.order;
		notifications = [];
		notificationsLoaded = false;
		notificationsError = null;
		paymentError = null;
	});

	let notifications = $state<Notification[]>([]);
	let notificationsError = $state<string | null>(null);
	let notificationsLoaded = $state(false);

	let paying = $state(false);
	let paymentError = $state<string | null>(null);

	async function loadNotifications() {
		notificationsError = null;
		try {
			notifications = await getNotifications(order.id);
		} catch (err) {
			notificationsError =
				err instanceof ApiError && err.status === 404
					? 'No notifications recorded for this order yet.'
					: 'Could not reach the Gateway.';
		} finally {
			notificationsLoaded = true;
		}
	}

	async function onProcessPayment() {
		paymentError = null;
		paying = true;
		try {
			await processPayment(order.id, order.totalAmount);
			order = await getOrder(order.id);
		} catch (err) {
			paymentError = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			paying = false;
		}
	}
</script>

<a href="/orders">&larr; Back to my orders</a>

<h1>Order {order.id}</h1>
<p>Status: <strong>{order.state}</strong></p>
<p>Total: {formatCurrency(order.totalAmount)}</p>
<p class="meta">Created {formatDateTime(order.createdAt)} &middot; updated {formatDateTime(order.updatedAt)}</p>

<h2>Items</h2>
<ul>
	{#each order.items as item (item.id)}
		<li>{item.quantity} &times; {item.productId} ({formatCurrency(item.unitPrice)} each)</li>
	{/each}
</ul>

{#if order.state === 'INVENTORY_RESERVED'}
	<button onclick={onProcessPayment} disabled={paying}>
		{paying ? 'Processing payment...' : 'Process payment'}
	</button>
	{#if paymentError}
		<p class="error">{paymentError}</p>
	{/if}
{/if}

<h2>Notifications</h2>
<p class="hint">
	Every order-lifecycle event (order.created, order.status-changed) persisted its own
	notification row in notification-service -- reloading this shows the trail as it grows.
</p>
<button onclick={loadNotifications}>
	{notificationsLoaded ? 'Reload notifications' : 'Load notifications'}
</button>
{#if notificationsError}
	<p class="error">{notificationsError}</p>
{:else if notificationsLoaded}
	<ul>
		{#each notifications as notification, i (i)}
			<li>
				<strong>{notification.eventType}</strong> &mdash; {notification.status}
				<span class="meta">({formatDateTime(notification.createdAt)})</span>
				<pre>{JSON.stringify(notification.payload, null, 2)}</pre>
			</li>
		{/each}
	</ul>
{/if}

<RequestDetails />

<style>
	.meta {
		color: #777;
		font-size: 0.85rem;
	}
	.error {
		color: #b00020;
	}
	.hint {
		color: #666;
		font-size: 0.85rem;
	}
	pre {
		background: #f7f7f7;
		padding: 0.5rem;
		border-radius: 4px;
		font-size: 0.8rem;
		overflow-x: auto;
	}
</style>
