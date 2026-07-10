-- Minimal read-model projection: answers only "does this seller own this
-- product?" for the self-purchase check in OrderService.createOrder.
-- Populated from products-service's product.created event; no other
-- catalog attribute (name, price, stock, description, category,
-- timestamps) belongs here.
CREATE TABLE orders_schema.product_ownership (
    product_id VARCHAR(255) PRIMARY KEY,
    seller_id VARCHAR(255) NOT NULL
);
