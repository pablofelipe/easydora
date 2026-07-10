package com.easydora.orders.consumer;

import com.easydora.correlation.BusinessEventLog;
import com.easydora.correlation.CorrelationConstants;
import com.easydora.correlation.CorrelationContext;
import com.easydora.orders.config.RabbitMQConfig;
import com.easydora.orders.entity.ProductOwnership;
import com.easydora.orders.event.ProductCreatedEvent;
import com.easydora.orders.repository.ProductOwnershipRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Builds the local product-ownership projection this service uses for the
 * self-purchase check in OrderService.createOrder -- the only alternative
 * would be a synchronous call to products-service for every order, which
 * this project's architecture deliberately avoids (see ADR-0026).
 */
@Component
public class ProductEventsConsumer {

    private static final Logger logger = LoggerFactory.getLogger(ProductEventsConsumer.class);

    private final ProductOwnershipRepository productOwnershipRepository;

    public ProductEventsConsumer(ProductOwnershipRepository productOwnershipRepository) {
        this.productOwnershipRepository = productOwnershipRepository;
    }

    @RabbitListener(queues = RabbitMQConfig.PRODUCT_CREATED_QUEUE)
    public void onProductCreated(
            ProductCreatedEvent event,
            @Header(name = AmqpHeaders.CORRELATION_ID, required = false) String correlationId,
            @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY,
                correlationId != null ? correlationId : CorrelationContext.newCorrelationId());
        MDC.put(CorrelationConstants.MESSAGE_ID_MDC_KEY, messageId);
        try {
            BusinessEventLog.info(logger, "product.created.received", event.getProductId(),
                    "Recording ownership: seller=" + event.getSellerId());
            productOwnershipRepository.save(
                    new ProductOwnership(event.getProductId(), event.getSellerId()));
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.MESSAGE_ID_MDC_KEY);
        }
    }
}
