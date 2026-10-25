package com.vanmool.userauthservice.configs;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

// Kafka: this service owns the user-events topic, and its OutboxRelay runs on a schedule
@Configuration
@EnableScheduling
public class KafkaConfig {
    public static final String USER_EVENTS_TOPIC = "user-events";

    // Created at startup if it does not exist (Spring's KafkaAdmin). 3 partitions: up to 3 consumers of one group can
    // share the work. Events are keyed by user id, so one user's events stay in order on one partition.
    // 1 replica, because the local broker is the only one; a production cluster would use 3.
    @Bean
    public NewTopic userEventsTopic() {
        return TopicBuilder.name(USER_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
