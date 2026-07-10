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
		margin-top: 1rem;
		border: 1px solid #ddd;
		border-radius: 4px;
		padding: 0.5rem 0.75rem;
		font-size: 0.85rem;
	}
	summary {
		cursor: pointer;
		color: #555;
	}
	dl {
		display: grid;
		grid-template-columns: auto 1fr;
		gap: 0.25rem 0.75rem;
		margin: 0.5rem 0 0;
	}
	dt {
		font-weight: 600;
		color: #777;
	}
	dd {
		margin: 0;
		font-family: monospace;
		word-break: break-all;
	}
</style>
