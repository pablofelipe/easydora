<script lang="ts">
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
<main>
	{@render children()}
</main>

<style>
	main {
		max-width: 960px;
		margin: 0 auto;
		padding: 1.5rem;
	}
</style>
