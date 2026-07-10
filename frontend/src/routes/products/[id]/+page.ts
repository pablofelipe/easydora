import { getProduct } from '$lib/api/products';
import type { PageLoad } from './$types';

export const load: PageLoad = async ({ params }) => {
	return { product: await getProduct(params.id) };
};
