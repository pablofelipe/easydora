<script lang="ts">
	import { goto } from '$app/navigation';
	import { signup, verifyEmail } from '$lib/api/auth';
	import { ApiError } from '$lib/api/client';

	let email = $state('');
	let password = $state('');
	let firstName = $state('');
	let lastName = $state('');
	let role = $state<'BUYER' | 'SELLER'>('BUYER');
	let error = $state<string | null>(null);
	let submitting = $state(false);

	let verificationToken = $state<string | null>(null);
	let verifying = $state(false);
	let verifyError = $state<string | null>(null);

	async function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		error = null;
		submitting = true;
		try {
			const response = await signup({ email, password, firstName, lastName, role });
			verificationToken = response.verificationToken;
		} catch (err) {
			error = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			submitting = false;
		}
	}

	async function onVerify() {
		if (!verificationToken) return;
		verifyError = null;
		verifying = true;
		try {
			await verifyEmail(verificationToken);
			goto('/login');
		} catch (err) {
			verifyError = err instanceof ApiError ? err.message : 'Could not reach the Gateway.';
		} finally {
			verifying = false;
		}
	}
</script>

<div class="center">
	<div class="card">
		<h1>Create account</h1>

		{#if !verificationToken}
			<form onsubmit={onSubmit}>
				<label>
					First name
					<input type="text" bind:value={firstName} required />
				</label>
				<label>
					Last name
					<input type="text" bind:value={lastName} required />
				</label>
				<label>
					Email
					<input type="email" bind:value={email} required />
				</label>
				<label>
					Password
					<input type="password" bind:value={password} minlength="6" required />
				</label>
				<label>
					Account type
					<select bind:value={role}>
						<option value="BUYER">Buyer</option>
						<option value="SELLER">Seller</option>
					</select>
				</label>
				<button type="submit" disabled={submitting}>
					{submitting ? 'Creating account...' : 'Create account'}
				</button>
				{#if error}
					<p class="error-text">{error}</p>
				{/if}
			</form>
			<p class="text-muted footer-text">
				Already have an account? <a href="/login">Sign in</a>
			</p>
		{:else}
			<p class="text-muted">
				Account created. This project has no real email delivery, so the verification link is
				shown here directly instead of being emailed.
			</p>
			<button onclick={onVerify} disabled={verifying}>
				{verifying ? 'Verifying...' : 'Verify email and continue'}
			</button>
			{#if verifyError}
				<p class="error-text">{verifyError}</p>
			{/if}
		{/if}
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
		max-width: 360px;
	}
	h1 {
		text-align: center;
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
	button {
		margin-top: 0.25rem;
	}
	.footer-text {
		text-align: center;
		margin-top: 1rem;
	}
</style>
