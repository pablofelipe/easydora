package com.easydora.orders.event;

/**
 * products-service's real product.created payload also carries
 * productName/price/initialStock/createdAt (see products-service's own
 * ProductCreatedEvent) -- only productId/sellerId are declared here,
 * matching this service's own minimal-DTO convention (see PaymentEvent)
 * and the ownership projection's narrow scope. The shared
 * Jackson2JsonMessageConverter has FAIL_ON_UNKNOWN_PROPERTIES disabled, so
 * the extra fields on the wire are simply ignored, not rejected.
 *
 * Ownership is only ever set at product.created time -- if products-service
 * ever supports transferring a product to a different seller, a consumer
 * for that event can be added then; not anticipated here.
 */
public class ProductCreatedEvent {

    private String productId;
    private String sellerId;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }
}
