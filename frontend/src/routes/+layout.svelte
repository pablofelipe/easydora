<script lang="ts">
	import '../app.css';
	import { page } from '$app/stores';
	import { goto } from '$app/navigation';
	import favicon from '$lib/assets/favicon.svg';
	import Nav from '$lib/components/Nav.svelte';
	import { auth } from '$lib/stores/auth';

	let { children } = $props();

	const PUBLIC_PATHS = ['/login'];

	$effect(() => {
		const isPublic = PUBLIC_PATHS.includes($page.url.pathname);
		if (!$auth && !isPublic) {
			goto('/login');
		}
	});
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<Nav />
<main class="container">
	{@render children()}
</main>

<style>
	main {
		padding: 2rem 1.5rem 4rem;
	}
</style>
