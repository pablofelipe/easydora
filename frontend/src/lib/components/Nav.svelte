<script lang="ts">
	import { goto } from '$app/navigation';
	import { auth } from '$lib/stores/auth';

	function logout() {
		auth.logout();
		goto('/login');
	}
</script>

<nav>
	<div class="brand">EasyDora</div>
	{#if $auth}
		<div class="links">
			<a href="/products">Products</a>
			<a href="/checkout">Checkout</a>
			<a href="/orders">My Orders</a>
			<span class="user">{$auth.firstName} ({$auth.role})</span>
			<button onclick={logout}>Logout</button>
		</div>
	{/if}
</nav>

<style>
	nav {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.75rem 1.5rem;
		border-bottom: 1px solid #ddd;
	}
	.brand {
		font-weight: 700;
	}
	.links {
		display: flex;
		align-items: center;
		gap: 1rem;
	}
	.user {
		color: #666;
		font-size: 0.9rem;
	}
</style>
