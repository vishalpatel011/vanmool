package com.vanmool.orderservice.configs;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

// Kafka: this service owns the order-events topic, and its OutboxRelay runs on a schedule
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

    // The time written into outbox rows and events; a bean, so tests can fix it
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
