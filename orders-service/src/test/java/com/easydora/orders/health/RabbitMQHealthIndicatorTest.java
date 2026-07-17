package com.easydora.orders.health;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * order.exchange is declared as a TopicExchange in RabbitMQConfig -- the
 * fallback path here (only reached when the exchange doesn't exist yet, e.g.
 * right after a RabbitMQ restart with no PersistentVolume, ADR-0040) must
 * declare it with the same type. Declaring it as "direct" instead makes the
 * next real declaration of the same exchange (by RabbitMQConfig's own
 * TopicExchange bean, or by this project's automatic topology recovery)
 * fail with a type-mismatch error from the broker.
 */
class RabbitMQHealthIndicatorTest {

    @Test
    void declaresTheFallbackExchangeAsTopicNotDirect() throws IOException {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Channel channel = mock(Channel.class);
        doThrow(new IOException("exchange does not exist"))
                .when(channel).exchangeDeclarePassive("order.exchange");
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(channel);
        });

        RabbitMQHealthIndicator indicator = new RabbitMQHealthIndicator(rabbitTemplate);
        Health health = indicator.health();

        verify(channel).exchangeDeclare(eq("order.exchange"), eq("topic"), anyBoolean());
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
}
