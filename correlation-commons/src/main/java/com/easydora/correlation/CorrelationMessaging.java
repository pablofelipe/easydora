package com.easydora.correlation;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

/**
 * Single point of contact for attaching correlationId/messageId to a
 * directly-published (non-Outbox) message. Every RabbitTemplate.convertAndSend
 * call should pass {@link #withCorrelation()} (or
 * {@link #composedWith(MessagePostProcessor)} if the call site already has
 * its own MessagePostProcessor) as its MessagePostProcessor argument,
 * instead of setting these properties by hand at each call site.
 */
public final class CorrelationMessaging {

    private CorrelationMessaging() {
    }

    public static MessagePostProcessor withCorrelation() {
        return (Message message) -> {
            message.getMessageProperties().setCorrelationId(CorrelationContext.currentOrNewCorrelationId());
            message.getMessageProperties().setMessageId(CorrelationContext.newMessageId());
            return message;
        };
    }

    /** Runs an existing MessagePostProcessor first, then attaches
     * correlationId/messageId -- for call sites that already set other
     * message properties (e.g. content type) and shouldn't lose them. */
    public static MessagePostProcessor composedWith(MessagePostProcessor existing) {
        return (Message message) -> {
            Message afterExisting = existing.postProcessMessage(message);
            return withCorrelation().postProcessMessage(afterExisting);
        };
    }
}
