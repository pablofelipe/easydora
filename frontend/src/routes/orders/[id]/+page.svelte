<script lang="ts">
	import { getOrder, shipOrder, confirmDelivery, cancelOrder } from '$lib/api/orders';
	import { processPayment } from '$lib/api/billing';
	import { getNotifications } from '$lib/api/notifications';
	import { ApiError } from '$lib/api/client';
	import RequestDetails from '$lib/components/RequestDetails.svelte';
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { auth } from '$lib/stores/auth';
	import type { Order, OrderState } from '$lib/types/order';
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
		shipError = null;
		deliverError = null;
		cancelError = null;
	});

	// Mirrors OrderStateMachineConfig's CANCEL_ORDER transition, valid from
	// PENDING/PROCESSING/INVENTORY_RESERVED only.
	const CANCELLABLE_STATES: OrderState[] = ['PENDING', 'PROCESSING', 'INVENTORY_RESERVED'];

	let notifications = $state<Notification[]>([]);
	let notificationsError = $state<string | null>(null);
	let notificationsLoaded = $state(false);

	let paying = $state(false);
	let paymentError = $state<string | null>(null);

	let shipping = $state(false);
	let shipError = $state<string | null>(null);

	let delivering = $state(false);
	let deliverError = $state<string | null>(null);

	let cancelling = $state(false);
	let cancelError = $state<string | null>(null);

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
			await processPayment(order.id);
			order = await getOrder(order.id);
		} catch (err) {
			paymentError = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			paying = false;
		}
	}

	async function onShipOrder() {
		shipError = null;
		shipping = true;
		try {
			order = await shipOrder(order.id);
		} catch (err) {
			shipError = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			shipping = false;
		}
	}

	async function onConfirmDelivery() {
		deliverError = null;
		delivering = true;
		try {
			order = await confirmDelivery(order.id);
		} catch (err) {
			deliverError = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			delivering = false;
		}
	}

	async function onCancelOrder() {
		cancelError = null;
		cancelling = true;
		try {
			order = await cancelOrder(order.id);
		} catch (err) {
			cancelError = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			cancelling = false;
		}
	}
</script>

<a href="/orders">&larr; Back to my orders</a>

<div class="card header-card">
	<div class="title-row">
		<h1>Order <code>{order.id.slice(0, 8)}&hellip;</code></h1>
		<StatusBadge state={order.state} />
	</div>
	<p class="total">{formatCurrency(order.totalAmount)}</p>
	<p class="text-muted">
		Created {formatDateTime(order.createdAt)} &middot; updated {formatDateTime(order.updatedAt)}
	</p>

	{#if order.state === 'INVENTORY_RESERVED'}
		<button onclick={onProcessPayment} disabled={paying}>
			{paying ? 'Processing payment...' : 'Process payment'}
		</button>
		{#if paymentError}
			<p class="error-text">{paymentError}</p>
		{/if}
	{/if}

	{#if order.state === 'PAYMENT_APPROVED' && $auth?.role === 'ADMIN'}
		<button onclick={onShipOrder} disabled={shipping}>
			{shipping ? 'Marking as shipped...' : 'Mark as shipped'}
		</button>
		{#if shipError}
			<p class="error-text">{shipError}</p>
		{/if}
	{/if}

	{#if order.state === 'SHIPPED' && $auth?.role === 'BUYER'}
		<button onclick={onConfirmDelivery} disabled={delivering}>
			{delivering ? 'Confirming delivery...' : 'Confirm delivery'}
		</button>
		{#if deliverError}
			<p class="error-text">{deliverError}</p>
		{/if}
	{/if}

	{#if $auth?.role === 'BUYER' && CANCELLABLE_STATES.includes(order.state)}
		<button class="danger" onclick={onCancelOrder} disabled={cancelling}>
			{cancelling ? 'Cancelling order...' : 'Cancel order'}
		</button>
		{#if cancelError}
			<p class="error-text">{cancelError}</p>
		{/if}
	{/if}
</div>

<h2>Items</h2>
<div class="card">
	<ul class="items">
		{#each order.items as item (item.id)}
			<li>
				<span>{item.quantity} &times; <code class="text-muted">{item.productId.slice(0, 8)}&hellip;</code></span>
				<span class="subtotal">{formatCurrency(item.subtotal)}</span>
			</li>
		{/each}
	</ul>
</div>

<h2>Notifications</h2>
<p class="text-muted">
	Every order-lifecycle event (order.created, order.status-changed) persisted its own
	notification row in notification-service -- reloading this shows the trail as it grows.
</p>
<button onclick={loadNotifications}>
	{notificationsLoaded ? 'Reload notifications' : 'Load notifications'}
</button>
{#if notificationsError}
	<p class="error-text">{notificationsError}</p>
{:else if notificationsLoaded}
	<ul class="notifications">
		{#each notifications as notification, i (i)}
			<li class="card">
				<div class="notif-row">
					<span class="event">{notification.eventType}</span>
					<span class="badge {notification.status === 'SENT' ? 'badge-green' : 'badge-red'}">
						{notification.status}
					</span>
					<span class="text-muted time">{formatDateTime(notification.createdAt)}</span>
				</div>
				<details>
					<summary>View raw payload</summary>
					<pre>{JSON.stringify(notification.payload, null, 2)}</pre>
				</details>
			</li>
		{/each}
	</ul>
{/if}

<RequestDetails />

<style>
	.header-card {
		margin-top: 1rem;
	}
	.header-card button.danger {
		background: var(--color-danger-bg);
		border-color: var(--color-danger);
		color: var(--color-danger);
	}
	.header-card button.danger:hover {
		background: var(--color-danger);
		color: #fff;
	}
	.title-row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}
	.title-row h1 {
		margin: 0;
	}
	.total {
		font-size: 1.25rem;
		font-weight: 700;
		margin: 0.5rem 0 0.25rem;
	}
	.items {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.items li {
		display: flex;
		justify-content: space-between;
		padding: 0.4rem 0;
	}
	.items li + li {
		border-top: 1px solid var(--color-border);
	}
	.subtotal {
		font-weight: 600;
	}
	.notifications {
		list-style: none;
		margin: 0.75rem 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
	}
	.notif-row {
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}
	.event {
		font-weight: 600;
	}
	.time {
		margin-left: auto;
	}
	details {
		margin-top: 0.5rem;
	}
	details summary {
		cursor: pointer;
		font-size: 0.8rem;
		color: var(--color-text-muted);
	}
	pre {
		background: var(--color-bg);
		padding: 0.6rem;
		border-radius: var(--radius-sm);
		font-size: 0.78rem;
		overflow-x: auto;
		margin-top: 0.5rem;
	}
</style>
