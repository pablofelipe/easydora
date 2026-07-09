package com.easydora.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationMessagingTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesTheCurrentCorrelationIdFromMdcAndGeneratesAFreshMessageId() {
        MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, "corr-in-flight");

        MessagePostProcessor processor = CorrelationMessaging.withCorrelation();
        Message message = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));

        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo("corr-in-flight");
        assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
    }

    @Test
    void generatesAFreshCorrelationIdWhenNoneIsInFlight() {
        MessagePostProcessor processor = CorrelationMessaging.withCorrelation();
        Message message = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));

        assertThat(message.getMessageProperties().getCorrelationId()).isNotBlank();
    }

    @Test
    void generatesADifferentMessageIdOnEachCall() {
        MessagePostProcessor processor = CorrelationMessaging.withCorrelation();
        Message first = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));
        Message second = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));

        assertThat(first.getMessageProperties().getMessageId())
                .isNotEqualTo(second.getMessageProperties().getMessageId());
    }

    @Test
    void composesWithAnExistingMessagePostProcessorWithoutLosingItsChanges() {
        MessagePostProcessor contentTypeSetter = message -> {
            message.getMessageProperties().setContentType("application/json");
            return message;
        };

        MessagePostProcessor combined = CorrelationMessaging.composedWith(contentTypeSetter);
        Message message = combined.postProcessMessage(new Message(new byte[0], new MessageProperties()));

        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getCorrelationId()).isNotBlank();
        assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
    }
}
