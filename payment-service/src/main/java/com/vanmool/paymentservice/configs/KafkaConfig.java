package com.vanmool.paymentservice.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vanmool.paymentservice.events.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

// Kafka: this service owns the payment-events topic and reads order-events. Scheduling runs the outbox relay and the
// reconciliation
@Configuration
@EnableScheduling
public class KafkaConfig {
    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    // Keyed by order id, so an order's payment events stay in order on one partition
    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    public static final String ORDER_EVENTS_DLT = "order-events.payment-service.DLT";

    @Bean
    public NewTopic orderEventsDeadLetterTopic() {
        return TopicBuilder.name(ORDER_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    // As in the other consumers: 10 retries 2 s apart (a gateway may be briefly down), then this service's dead-letter
    // topic; unreadable messages at once
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(ORDER_EVENTS_DLT, -1));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000, 10));
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class, MalformedEventException.class);
        return errorHandler;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
