import { getOrder } from '$lib/api/orders';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ params }) => {
	return { order: await getOrder(params.id) };
};
