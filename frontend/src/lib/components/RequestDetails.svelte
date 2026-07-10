<script lang="ts">
	import { lastRequest } from '$lib/stores/requestTrace';

	let open = $state(false);
</script>

{#if $lastRequest}
	<details bind:open>
		<summary>Request Details</summary>
		<dl>
			<dt>Method / Path</dt>
			<dd>{$lastRequest.method} {$lastRequest.path}</dd>
			<dt>Status</dt>
			<dd>{$lastRequest.status}</dd>
			<dt>Correlation ID</dt>
			<dd>{$lastRequest.correlationId ?? 'n/a'}</dd>
			<dt>Request ID</dt>
			<dd>{$lastRequest.requestId ?? 'n/a'}</dd>
		</dl>
	</details>
{/if}

<style>
	details {
		margin-top: 1.5rem;
		border: 1px solid var(--color-border);
		border-radius: var(--radius-sm);
		padding: 0.6rem 0.85rem;
		font-size: 0.85rem;
		background: var(--color-surface);
	}
	summary {
		cursor: pointer;
		color: var(--color-text-muted);
		font-weight: 500;
	}
	dl {
		display: grid;
		grid-template-columns: auto 1fr;
		gap: 0.35rem 0.75rem;
		margin: 0.6rem 0 0;
	}
	dt {
		font-weight: 600;
		color: var(--color-text-muted);
	}
	dd {
		margin: 0;
		word-break: break-all;
	}
</style>
