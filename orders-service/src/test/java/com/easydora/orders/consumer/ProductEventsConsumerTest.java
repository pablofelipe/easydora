package com.easydora.orders.consumer;

import com.easydora.orders.entity.ProductOwnership;
import com.easydora.orders.event.ProductCreatedEvent;
import com.easydora.orders.repository.ProductOwnershipRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * ownership is captured the moment a product is created (see
 * ProductCreatedEvent's docstring for why product.created alone is
 * enough today). Only productId/sellerId are read out of the event --
 * name/price/initialStock/createdAt are all ignored by design, per the
 * "answer exactly one question" scope of this projection.
 */
@ExtendWith(MockitoExtension.class)
class ProductEventsConsumerTest {

    @Mock
    private ProductOwnershipRepository productOwnershipRepository;

    @Test
    void productCreatedPopulatesTheOwnershipProjection() {
        ProductEventsConsumer consumer = new ProductEventsConsumer(productOwnershipRepository);

        ProductCreatedEvent event = new ProductCreatedEvent();
        event.setProductId("prod-1");
        event.setSellerId("42");

        consumer.onProductCreated(event, "corr-1", "msg-1");

        ArgumentCaptor<ProductOwnership> captor = ArgumentCaptor.forClass(ProductOwnership.class);
        verify(productOwnershipRepository).save(captor.capture());

        ProductOwnership saved = captor.getValue();
        assertThat(saved.getProductId()).isEqualTo("prod-1");
        assertThat(saved.getSellerId()).isEqualTo("42");
    }
}
