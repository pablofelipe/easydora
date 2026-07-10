<script lang="ts">
	import { goto } from '$app/navigation';
	import { login } from '$lib/api/auth';
	import { ApiError } from '$lib/api/client';
	import { auth } from '$lib/stores/auth';

	let email = $state('');
	let password = $state('');
	let error = $state<string | null>(null);
	let submitting = $state(false);

	async function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		error = null;
		submitting = true;
		try {
			const response = await login(email, password);
			auth.login({
				token: response.token,
				userId: response.userId,
				email: response.email,
				firstName: response.firstName,
				lastName: response.lastName,
				role: response.role
			});
			goto('/products');
		} catch (err) {
			error = err instanceof ApiError ? 'Invalid email or password.' : 'Could not reach the Gateway.';
		} finally {
			submitting = false;
		}
	}
</script>

<h1>Login</h1>

<form onsubmit={onSubmit}>
	<label>
		Email
		<input type="email" bind:value={email} required />
	</label>
	<label>
		Password
		<input type="password" bind:value={password} required />
	</label>
	<button type="submit" disabled={submitting}>{submitting ? 'Signing in...' : 'Sign in'}</button>
	{#if error}
		<p class="error">{error}</p>
	{/if}
</form>

<style>
	form {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		max-width: 320px;
	}
	label {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}
	.error {
		color: #b00020;
	}
</style>
