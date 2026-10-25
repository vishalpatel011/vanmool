package com.vanmool.notificationservice.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vanmool.notificationservice.events.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// When an email cannot be sent (the SMTP server is down), the event is tried again every 2 s, 10 times; after that,
// or at once for a message that can never work, it goes to this service's dead-letter topic for its source topic
// (user-events.notification-service.DLT, order-events.notification-service.DLT), with the error in its headers.
@Configuration
public class KafkaConfig {
    static final String DLT_SUFFIX = ".notification-service.DLT";

    @Bean
    public NewTopic userEventsDeadLetterTopic() {
        return TopicBuilder.name("user-events" + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDeadLetterTopic() {
        return TopicBuilder.name("order-events" + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    // Boot gives this error handler to every @KafkaListener
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000, 10));
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class, MalformedEventException.class);
        return errorHandler;
    }
}
