<script lang="ts">
	import type { OrderState } from '$lib/types/order';
	import { badgeClassFor } from '$lib/utils/status';

	let { state }: { state: OrderState } = $props();
</script>

<span class="badge {badgeClassFor(state)}">{state.replace(/_/g, ' ')}</span>
