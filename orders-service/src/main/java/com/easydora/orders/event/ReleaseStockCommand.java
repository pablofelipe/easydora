package com.easydora.orders.event;

import java.util.List;

public class ReleaseStockCommand {
    private String orderId;
    private List<OrderItemDTO> items;
    
    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public List<OrderItemDTO> getItems() {
        return items;
    }

    public void setItems(List<OrderItemDTO> items) {
        this.items = items;
    }

    public static class OrderItemDTO {
        private String productId;
        private Integer quantity;
        
        public String getProductId() {
            return productId;
        }
        public void setProductId(String productId) {
            this.productId = productId;
        }
        public Integer getQuantity() {
            return quantity;
        }
        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }
}