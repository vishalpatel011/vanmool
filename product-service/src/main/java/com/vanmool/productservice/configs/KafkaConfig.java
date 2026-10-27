package com.vanmool.productservice.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vanmool.productservice.events.MalformedEventException;
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

// What happens when handling an event fails:
//   - MySQL unreachable: tried again every 5 s until it is back (backOffFor), while later events of the same partition
//     wait, so the order of events is kept;
//   - another failure that may pass: tried again every 2 s, 10 times, the partition waiting likewise;
//   - still failing, or a message that can never work (not JSON, missing fields): published to this service's
//     dead-letter topic with the error in its headers, and the consumer moves on. Nothing is lost; the dead-letter
//     topic can be read and the events replayed once the cause is fixed.
@Configuration
public class KafkaConfig {
    // One dead-letter topic per consuming service: a message that product-service could not handle may be fine for
    // notification-service, so the two must not share one
    public static final String ORDER_EVENTS_DLT = "order-events.product-service.DLT";

    @Bean
    public NewTopic orderEventsDeadLetterTopic() {
        return TopicBuilder.name(ORDER_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    // Boot gives this error handler to every @KafkaListener
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(ORDER_EVENTS_DLT, -1));
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
}
