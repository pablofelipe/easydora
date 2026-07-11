package com.easydora.products.messaging;

import com.easydora.products.dto.ProductRequest;
import com.easydora.products.entity.Product;
import com.easydora.products.entity.Seller;
import com.easydora.products.entity.UserRole;
import com.easydora.products.repository.ProductRepository;
import com.easydora.products.repository.SellerRepository;
import com.easydora.products.service.ProductService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavior contract for the products -> inventory hop (ADR-0007, migrated
 * from Kafka to RabbitMQ): creating, updating or deleting a product must
 * publish a domain event some other service can react to. No assertion here inspects a
 * RabbitMQ-specific wire detail — only the business-level exchange/routing
 * key and payload.
 *
 * RecordingRabbitTemplate is a test double for ProductService's real
 * RabbitTemplate dependency: it overrides convertAndSend() to capture the
 * call instead of touching a real broker, so this test exercises the real
 * createProduct/updateProduct/deleteProduct methods against production
 * code, unchanged.
 */
@ExtendWith(MockitoExtension.class)
class ProductEventsPublishBehaviorTest {

    private static class RecordingRabbitTemplate extends RabbitTemplate {
        final List<String> routingKeys = new ArrayList<>();
        final List<Object> payloads = new ArrayList<>();

        RecordingRabbitTemplate() {
            super(mock(ConnectionFactory.class));
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            routingKeys.add(routingKey);
            payloads.add(object);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object, MessagePostProcessor messagePostProcessor) {
            routingKeys.add(routingKey);
            payloads.add(object);
        }
    }

    @Mock
    private ProductRepository productRepository;
    @Mock
    private SellerRepository sellerRepository;

    private Seller activeSeller(String sellerId) {
        Seller seller = new Seller();
        seller.setUserId(sellerId);
        seller.setRole(UserRole.SELLER);
        seller.setActive(true);
        return seller;
    }

    @Test
    void creatingAProductPublishesAProductCreatedEvent() {
        when(sellerRepository.findById("seller-1")).thenReturn(Optional.of(activeSeller("seller-1")));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId("prod-1");
            return product;
        });

        RecordingRabbitTemplate publisher = new RecordingRabbitTemplate();
        ProductService productService = new ProductService(productRepository, sellerRepository, publisher);

        ProductRequest request = new ProductRequest();
        request.setName("Widget");
        request.setDescription("A widget");
        request.setPrice(new BigDecimal("19.90"));
        request.setInitialStock(10);

        productService.createProduct(request, "seller-1");

        assertThat(publisher.routingKeys)
                .withFailMessage("creating a product should publish a product-created event")
                .contains("product.created");
    }

    @Test
    void updatingAProductPublishesAProductUpdatedEvent() {
        Product existing = new Product();
        existing.setId("prod-2");
        existing.setSeller(activeSeller("seller-2"));
        existing.setActive(true);

        when(productRepository.findById("prod-2")).thenReturn(Optional.of(existing));
        when(sellerRepository.findById("seller-2")).thenReturn(Optional.of(activeSeller("seller-2")));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingRabbitTemplate publisher = new RecordingRabbitTemplate();
        ProductService productService = new ProductService(productRepository, sellerRepository, publisher);

        ProductRequest request = new ProductRequest();
        request.setName("Widget v2");
        request.setPrice(new BigDecimal("24.90"));

        productService.updateProduct("prod-2", request, "seller-2");

        assertThat(publisher.routingKeys)
                .withFailMessage("updating a product should publish a product-updated event")
                .contains("product.updated");
    }

    @Test
    void deletingAProductPublishesAProductDeletedEvent() {
        Product existing = new Product();
        existing.setId("prod-3");
        existing.setSeller(activeSeller("seller-3"));
        existing.setActive(true);

        when(productRepository.findById("prod-3")).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingRabbitTemplate publisher = new RecordingRabbitTemplate();
        ProductService productService = new ProductService(productRepository, sellerRepository, publisher);

        productService.deleteProduct("prod-3", "seller-3");

        assertThat(publisher.routingKeys)
                .withFailMessage("deleting a product should publish a product-deleted event")
                .contains("product.deleted");
    }
}
