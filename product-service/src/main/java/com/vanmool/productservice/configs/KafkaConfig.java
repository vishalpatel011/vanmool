package com.vanmool.productservice.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vanmool.productservice.events.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// What happens when handling an event fails:
//   - a temporary failure (MySQL briefly down): tried again every 2 s, 10 times, while later events of the same
//     partition wait, so the order of events is kept;
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
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class, MalformedEventException.class);
        return errorHandler;
    }
}
