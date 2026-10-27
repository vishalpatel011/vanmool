package com.vanmool.orderservice.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vanmool.orderservice.events.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

// Kafka: this service owns the order-events topic (its OutboxRelay runs on a schedule) and reads payment-events
@Configuration
@EnableScheduling
public class KafkaConfig {
    public static final String ORDER_EVENTS_TOPIC = "order-events";

    // Created at startup if it does not exist. Events are keyed by order id, so an order's events (placed, then
    // cancelled) stay in order on one partition. 1 replica: the local broker is the only one
    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    public static final String PAYMENT_EVENTS_DLT = "payment-events.order-service.DLT";

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    // As in the other consumers: while the database is unreachable, tried until it is back (backOffFor); other failures
    // 10 times 2 s apart, then this service's dead-letter topic; unreadable messages at once
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(PAYMENT_EVENTS_DLT, -1));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000, 10));
        errorHandler.setBackOffFunction((record, exception) -> backOffFor(exception));
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class, MalformedEventException.class);
        return errorHandler;
    }

    // Handling an event failed: how long to keep trying. While a database is unreachable, every 5 s for as long as that
    // lasts: no other instance could handle the event either, and on the dead-letter topic it would wait for someone to
    // replay it (a paid order never confirmed, units never released). The partition waits meanwhile, so the order of
    // events is kept. Any other failure: 10 tries 2 s apart, then the dead-letter topic
    static BackOff backOffFor(Exception exception) {
        return isDatabaseUnavailable(exception)
                ? new FixedBackOff(5_000, FixedBackOff.UNLIMITED_ATTEMPTS)
                : new FixedBackOff(2_000, 10);
    }

    // No connection could be had (CannotCreateTransactionException), or it broke (DataAccessResourceFailureException);
    // the listener's failure wraps them
    static boolean isDatabaseUnavailable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException || cause instanceof DataAccessResourceFailureException) {
                return true;
            }
        }
        return false;
    }

    // The time written into outbox rows and events; a bean, so tests can fix it
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
