<script lang="ts">
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import { auth } from '$lib/stores/auth';

	function logout() {
		auth.logout();
		goto('/login');
	}

	const links = [
		{ href: '/products', label: 'Products' },
		{ href: '/checkout', label: 'Checkout' },
		{ href: '/orders', label: 'My Orders' }
	];
	const adminLinks = [
		{ href: '/fulfillment', label: 'Fulfillment' },
		{ href: '/refunds', label: 'Refunds' }
	];
	const sellerLinks = [{ href: '/products/new', label: 'New product' }];
</script>

<nav>
	<div class="container bar">
		<a class="brand" href="/products">EasyDora</a>
		{#if $auth}
			<div class="links">
				{#each links as link (link.href)}
					<a href={link.href} class:active={$page.url.pathname.startsWith(link.href)}>
						{link.label}
					</a>
				{/each}
				{#if $auth.role === 'ADMIN'}
					{#each adminLinks as link (link.href)}
						<a href={link.href} class:active={$page.url.pathname.startsWith(link.href)}>
							{link.label}
						</a>
					{/each}
				{/if}
				{#if $auth.role === 'SELLER'}
					{#each sellerLinks as link (link.href)}
						<a href={link.href} class:active={$page.url.pathname === link.href}>
							{link.label}
						</a>
					{/each}
				{/if}
			</div>
			<div class="right">
				<span class="user">{$auth.firstName}</span>
				<button onclick={logout}>Logout</button>
			</div>
		{/if}
	</div>
</nav>

<style>
	nav {
		border-bottom: 1px solid var(--color-border);
		background: var(--color-surface);
	}
	.bar {
		display: flex;
		align-items: center;
		gap: 2rem;
		height: 56px;
	}
	.brand {
		font-weight: 700;
		color: var(--color-text);
		letter-spacing: -0.01em;
	}
	.brand:hover {
		text-decoration: none;
	}
	.links {
		display: flex;
		align-items: center;
		gap: 1.5rem;
		flex: 1;
	}
	.links a {
		color: var(--color-text-muted);
		font-size: 0.9rem;
		font-weight: 500;
		padding: 0.35rem 0;
		border-bottom: 2px solid transparent;
	}
	.links a:hover {
		color: var(--color-text);
		text-decoration: none;
	}
	.links a.active {
		color: var(--color-text);
		border-bottom-color: var(--color-primary);
	}
	.right {
		display: flex;
		align-items: center;
		gap: 0.9rem;
	}
	.user {
		color: var(--color-text-muted);
		font-size: 0.85rem;
	}
	.right button {
		height: 32px;
		padding: 0 0.75rem;
		font-size: 0.82rem;
	}
</style>
