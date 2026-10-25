package com.vanmool.userauthservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.userauthservice.events.OutboxWriter;
import com.vanmool.userauthservice.events.UserRegisteredEvent;
import com.vanmool.userauthservice.exceptions.EmailAlreadyRegisteredException;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.services.UserService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Signup → outbox row → Kafka, against the MySQL in DB_URL and the Kafka broker started by docker compose. A plain
// Kafka consumer reads the user-events topic, as notification-service will, and must find the event for the new user.
@SpringBootTest
class OutboxEventsIT {
    private final String email = "outbox-it-" + UUID.randomUUID() + "@example.com";

    @Autowired
    private UserService userService;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void removeTestUser() {
        jdbcTemplate.update("DELETE e FROM outbox_event e JOIN users u ON e.message_key = BIN_TO_UUID(u.id) WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE r FROM user_role r JOIN users u ON u.id = r.user_id WHERE u.email = ?", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
    }

    private static KafkaConsumer<String, String> consumerFromTheStart() {
        // A new consumer group each time, reading the topic from the beginning
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    @Test
    void signup_isPublishedOnUserEvents_keyedByTheUserId() throws Exception {
        // Act
        User user = userService.signup("Outbox Test", email, "correct horse battery");

        // Assert: the relay (every second) sends the outbox row; read the topic until our user's event shows up
        String key = user.getId().toString();
        JsonNode event = null;
        try (KafkaConsumer<String, String> consumer = consumerFromTheStart()) {
            consumer.subscribe(List.of("user-events"));
            Instant deadline = Instant.now().plusSeconds(20);
            while (event == null && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        event = objectMapper.readTree(record.value());
                    }
                }
            }
        }
        assertNotNull(event, "no UserRegistered event for " + key + " within 20 s");
        assertEquals("UserRegistered", event.get("eventType").asText());
        assertEquals(email, event.get("email").asText());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE message_key = ? AND published_at IS NOT NULL", Integer.class, key));
    }

    @Test
    void failedSignup_leavesNoEvent() {
        // Arrange
        User user = userService.signup("Outbox Test", email, "correct horse battery");

        // Act: the same email again is refused
        assertThrows(EmailAlreadyRegisteredException.class,
                () -> userService.signup("Outbox Test", email, "correct horse battery"));

        // Assert: still only the first signup's event
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE message_key = ?", Integer.class, user.getId().toString()));
    }

    @Test
    void writingAnEventOutsideATransaction_isRefused() {
        // Propagation.MANDATORY: an event without the change it describes must never be saved
        assertThrows(IllegalTransactionStateException.class, () -> outboxWriter.add("user-events", "key",
                UserRegisteredEvent.of(UUID.randomUUID(), "Nobody", "nobody@example.com", Instant.now())));
    }
}
