package com.easydora.orders.actions;

import com.easydora.orders.event.ReleaseStockCommand;
import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.OrderItem;
import com.easydora.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import com.easydora.orders.statemachine.OrderState;
import com.easydora.orders.statemachine.OrderEvent;


@Component
public class ReleaseInventoryAction implements Action<OrderState, OrderEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(ReleaseInventoryAction.class);
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private OrderRepository orderRepository;
    
    @Override
    public void execute(StateContext<OrderState, OrderEvent> context) {
        try {
            // Extract orderId from the context
            Object orderIdObj = context.getMessage().getHeaders().get("orderId");
            if (orderIdObj == null) {
                log.error("OrderId not found in context");
                return;
            }

            String orderId = orderIdObj.toString();
            log.info("Releasing stock for order: {}", orderId);

            // Look up the order
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // Create the release command
            ReleaseStockCommand command = createReleaseCommand(order);

            // Send to RabbitMQ
            rabbitTemplate.convertAndSend(
                "order.exchange",
                "stock.release",
                command
            );

            log.info("Stock released for order: {}", orderId);

        } catch (Exception e) {
            log.error("Error releasing stock: {}", e.getMessage(), e);
            throw new RuntimeException("Error in release action", e);
        }
    }

    private ReleaseStockCommand createReleaseCommand(Order order) {
        ReleaseStockCommand command = new ReleaseStockCommand();
        command.setOrderId(order.getId());

        // Convert items
        java.util.List<ReleaseStockCommand.OrderItemDTO> items = new java.util.ArrayList<>();
        for (OrderItem item : order.getItems()) {
            ReleaseStockCommand.OrderItemDTO dto = new ReleaseStockCommand.OrderItemDTO();
            dto.setProductId(item.getProductId());
            dto.setQuantity(item.getQuantity());
            items.add(dto);
        }
        
        command.setItems(items);
        return command;
    }
}