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

<div class="center">
	<div class="card">
		<h1>Sign in</h1>
		<form onsubmit={onSubmit}>
			<label>
				Email
				<input type="email" bind:value={email} required />
			</label>
			<label>
				Password
				<input type="password" bind:value={password} required />
			</label>
			<button type="submit" disabled={submitting}>
				{submitting ? 'Signing in...' : 'Sign in'}
			</button>
			{#if error}
				<p class="error-text">{error}</p>
			{/if}
		</form>
		<p class="text-muted footer-text">
			Don't have an account? <a href="/signup">Sign up</a>
		</p>
	</div>
</div>

<style>
	.center {
		display: flex;
		justify-content: center;
		padding-top: 3rem;
	}
	.card {
		width: 100%;
		max-width: 340px;
	}
	h1 {
		text-align: center;
	}
	.footer-text {
		text-align: center;
		margin-top: 1rem;
	}
	form {
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}
	label {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}
	button[type='submit'] {
		margin-top: 0.25rem;
	}
</style>
