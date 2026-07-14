package com.easydora.orders.service;

import com.easydora.orders.dto.OrderItemRequest;
import com.easydora.orders.dto.OrderRequest;
import com.easydora.orders.entity.Buyer;
import com.easydora.orders.entity.Order;
import com.easydora.orders.entity.ProductOwnership;
import com.easydora.orders.repository.BuyerRepository;
import com.easydora.orders.repository.OrderRepository;
import com.easydora.orders.repository.ProductOwnershipRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A SELLER may buy normally, including another seller's products -- the
 * only forbidden case is buying a product they themselves own. orders-service
 * has no synchronous way to ask products-service who owns a product (see
 * ADR-0025/0026's "no new synchronous inter-service call" precedent), so
 * this check reads from a local projection (ProductOwnershipRepository)
 * built from product.created events instead.
 */
@ExtendWith(MockitoExtension.class)
class SelfPurchasePreventionTest {

    private static class RecordingRabbitTemplate extends RabbitTemplate {
        final List<String> routingKeys = new ArrayList<>();

        RecordingRabbitTemplate() {
            super(mock(ConnectionFactory.class));
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            routingKeys.add(routingKey);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object, MessagePostProcessor messagePostProcessor) {
            routingKeys.add(routingKey);
        }
    }

    @Mock
    private BuyerRepository buyerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderStateMachineService stateMachineService;
    @Mock
    private ProductOwnershipRepository productOwnershipRepository;

    private OrderRequest requestFor(String productId) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("10.00"));

        OrderRequest request = new OrderRequest();
        request.setItems(List.of(item));
        return request;
    }

    @Test
    void sellerCannotPurchaseTheirOwnProduct() {
        Buyer seller = new Buyer();
        seller.setUserId(42L);
        seller.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(seller));
        when(productOwnershipRepository.findById("prod-1"))
                .thenReturn(Optional.of(new ProductOwnership("prod-1", "42")));

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = new OrderService(
                buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository, new SimpleMeterRegistry());

        assertThatThrownBy(() -> orderService.createOrder(requestFor("prod-1"), 42L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cannot purchase your own product");

        verify(orderRepository, never()).save(any(Order.class));
        assertThat(rabbitTemplate.routingKeys)
                .withFailMessage("a rejected self-purchase must not publish any event")
                .isEmpty();
    }

    @Test
    void sellerCanPurchaseAnotherSellersProduct() {
        Buyer seller = new Buyer();
        seller.setUserId(42L);
        seller.setActive(true);
        when(buyerRepository.findById(42L)).thenReturn(Optional.of(seller));
        when(productOwnershipRepository.findById("prod-1"))
                .thenReturn(Optional.of(new ProductOwnership("prod-1", "99")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent(anyString(), any())).thenReturn(true);
        when(stateMachineService.getCurrentState(anyString()))
                .thenReturn(com.easydora.orders.statemachine.OrderState.PROCESSING);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = new OrderService(
                buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository, new SimpleMeterRegistry());

        orderService.createOrder(requestFor("prod-1"), 42L);

        assertThat(rabbitTemplate.routingKeys).contains("order.created");
    }

    @Test
    void buyerCanPurchaseNormally() {
        Buyer buyer = new Buyer();
        buyer.setUserId(7L);
        buyer.setActive(true);
        when(buyerRepository.findById(7L)).thenReturn(Optional.of(buyer));
        when(productOwnershipRepository.findById("prod-1"))
                .thenReturn(Optional.of(new ProductOwnership("prod-1", "42")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent(anyString(), any())).thenReturn(true);
        when(stateMachineService.getCurrentState(anyString()))
                .thenReturn(com.easydora.orders.statemachine.OrderState.PROCESSING);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = new OrderService(
                buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository, new SimpleMeterRegistry());

        orderService.createOrder(requestFor("prod-1"), 7L);

        assertThat(rabbitTemplate.routingKeys).contains("order.created");
    }

    @Test
    void purchaseAllowedWhenOwnershipIsUnknown() {
        Buyer buyer = new Buyer();
        buyer.setUserId(7L);
        buyer.setActive(true);
        when(buyerRepository.findById(7L)).thenReturn(Optional.of(buyer));
        when(productOwnershipRepository.findById("prod-unknown")).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stateMachineService.sendEvent(anyString(), any())).thenReturn(true);
        when(stateMachineService.getCurrentState(anyString()))
                .thenReturn(com.easydora.orders.statemachine.OrderState.PROCESSING);

        RecordingRabbitTemplate rabbitTemplate = new RecordingRabbitTemplate();
        OrderService orderService = new OrderService(
                buyerRepository, orderRepository, stateMachineService, rabbitTemplate,
                productOwnershipRepository, new SimpleMeterRegistry());

        orderService.createOrder(requestFor("prod-unknown"), 7L);

        assertThat(rabbitTemplate.routingKeys).contains("order.created");
    }
}
