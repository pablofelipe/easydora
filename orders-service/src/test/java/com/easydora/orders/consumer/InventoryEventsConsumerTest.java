package com.easydora.orders.consumer;

import com.easydora.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * inventory-service (Go) used to publish only a raw orderId string on
 * stock-reserved/stock-insufficient; it now publishes the full event JSON
 * (see StockReservedEvent/StockInsufficientEvent). These tests confirm this
 * consumer correctly extracts orderId from that JSON payload — including
 * when the extra fields (productId, required, available) that used to be
 * silently dropped are present — instead of assuming the payload is still
 * a bare string.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventsConsumerTest {

    @Mock
    private OrderService orderService;

    @Test
    void handleStockReserved_extractsOrderIdFromFullEventJson() throws Exception {
        InventoryEventsConsumer consumer = new InventoryEventsConsumer(orderService);

        String payload = "{\"orderId\":\"order-1\",\"success\":true,\"message\":\"stock reserved\",\"timestamp\":\"2026-07-03T10:00:00Z\"}";
        consumer.handleStockReserved(payload);

        verify(orderService).handleInventoryReserved("order-1");
    }

    @Test
    void handleStockInsufficient_extractsOrderIdFromFullEventJsonWithProductFields() throws Exception {
        InventoryEventsConsumer consumer = new InventoryEventsConsumer(orderService);

        String payload = "{\"orderId\":\"order-2\",\"productId\":\"prod-1\",\"required\":5,\"available\":2,\"timestamp\":\"2026-07-03T10:00:00Z\"}";
        consumer.handleStockInsufficient(payload);

        verify(orderService).handleInventoryFailed("order-2");
    }
}
