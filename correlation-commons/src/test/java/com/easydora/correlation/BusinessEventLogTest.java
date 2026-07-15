package com.easydora.correlation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessEventLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("test.business-event-log");
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsEventAndAggregateIdInAConsistentKeyValueShape() {
        BusinessEventLog.info(logger, "order.created.published", "order-42", "Order created event published");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .isEqualTo("event=order.created.published aggregateId=order-42 msg=Order created event published");
    }

    @Test
    void logsErrorsInTheSameKeyValueShapeAtErrorLevel() {
        RuntimeException cause = new RuntimeException("broker unavailable");

        BusinessEventLog.error(logger, "order.created.publish_failed", "order-42", "Publish failed", cause);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .isEqualTo("event=order.created.publish_failed aggregateId=order-42 msg=Publish failed");
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("broker unavailable");
    }
}
