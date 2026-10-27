package com.vanmool.apigateway.configs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vanmool.apigateway.revocation.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;

// As in the other services that read events: an event that fails (Redis down) is tried again every 2 s, 10 times, then
// goes to the gateway's dead-letter topic for it, user-events.api-gateway.DLT; one that can never work goes there at once
@Configuration
public class RevocationConfig {
    static final String DLT_SUFFIX = ".api-gateway.DLT";

    @Bean
    public NewTopic userEventsDeadLetterTopic() {
        return TopicBuilder.name("user-events" + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000, 10));
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class, MalformedEventException.class);
        return errorHandler;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
