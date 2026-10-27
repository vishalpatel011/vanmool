package com.vanmool.notificationservice;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Event on Kafka → email in Mailpit, against the Kafka, Redis and Mailpit started by docker compose. Mailpit's HTTP
// API (http://localhost:8025/api/v1) is read to find the emails sent to this test's own addresses.
@SpringBootTest
class NotificationFlowIT {
    private final String address = "notification-it-" + UUID.randomUUID() + "@example.com";
    private final List<UUID> eventIds = new ArrayList<>();
    private final RestClient mailpit = RestClient.create("http://localhost:8025/api/v1");

    // user-auth-service and order-service own the topics this test sends to, and create them when they start. On a
    // Kafka that has not seen them yet (a new machine, or after docker compose down -v) the test declares them too,
    // with the same settings, so that they exist before the listeners subscribe and before the test sends
    @TestConfiguration
    static class SourceTopics {
        @Bean
        NewTopic userEventsTopic() {
            return TopicBuilder.name("user-events").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name("order-events").partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StringRedisTemplate redis;

    private UUID newEventId() {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        return eventId;
    }

    // The emails Mailpit holds for this test's address
    private JsonNode messagesToTestAddress() {
        return mailpit.get().uri("/search?query={query}", "to:\"" + address + "\"").retrieve().body(JsonNode.class).get("messages");
    }

    @AfterEach
    void removeEmailsAndHandledEvents() {
        List<String> ids = new ArrayList<>();
        messagesToTestAddress().forEach(message -> ids.add(message.get("ID").asText()));
        if (!ids.isEmpty()) {
            mailpit.method(org.springframework.http.HttpMethod.DELETE).uri("/messages").body(Map.of("IDs", ids))
                    .retrieve().toBodilessEntity();
        }
        eventIds.forEach(eventId -> redis.delete("notification:event:" + eventId));
    }

    @Test
    void userRegistered_sendsOneWelcomeEmail_evenWhenTheEventArrivesTwice() throws Exception {
        // Arrange
        UUID eventId = newEventId();
        String event = """
                { "eventId": "%s", "eventType": "UserRegistered", "occurredAt": "%s", "userId": "%s",
                  "name": "Notification Test", "email": "%s" }""".formatted(eventId, Instant.now(), UUID.randomUUID(), address);

        // Act: the same event twice, as at-least-once delivery allows
        kafkaTemplate.send("user-events", eventId.toString(), event).get();
        kafkaTemplate.send("user-events", eventId.toString(), event).get();

        // Assert: one email, and only one, after both copies were read
        await().atMost(Duration.ofSeconds(30)).until(() -> messagesToTestAddress().size() == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(redis.hasKey("notification:event:" + eventId)));
        Thread.sleep(2_000);
        JsonNode messages = messagesToTestAddress();
        assertEquals(1, messages.size());
        assertEquals("Welcome to Vanmool", messages.get(0).get("Subject").asText());
    }

    @Test
    void orderPlaced_sendsTheConfirmationWithItemsAndTotal() {
        // Arrange
        UUID eventId = newEventId();
        UUID orderId = UUID.randomUUID();
        String event = """
                { "eventId": "%s", "eventType": "OrderPlaced", "occurredAt": "%s", "orderId": "%s", "userId": "%s",
                  "customerEmail": "%s",
                  "items": [ { "productId": "%s", "name": "Handcrafted Bamboo Basket", "unitPrice": 499.99, "quantity": 2 },
                             { "productId": "%s", "name": "Ragi Biscuits", "unitPrice": 120.00, "quantity": 3 } ],
                  "totalAmount": 1359.98,
                  "shippingAddress": { "name": "Asha Gamit", "phone": "9876543210", "line1": "12 Station Road",
                                       "line2": null, "city": "Ahwa", "state": "Gujarat", "pincode": "394710" } }"""
                .formatted(eventId, Instant.now(), orderId, UUID.randomUUID(), address, UUID.randomUUID(), UUID.randomUUID());

        // Act
        kafkaTemplate.send("order-events", orderId.toString(), event);

        // Assert
        await().atMost(Duration.ofSeconds(30)).until(() -> messagesToTestAddress().size() == 1);
        JsonNode summary = messagesToTestAddress().get(0);
        assertEquals("Your Vanmool order " + orderId.toString().substring(0, 8) + " is placed", summary.get("Subject").asText());
        String text = mailpit.get().uri("/message/{id}", summary.get("ID").asText()).retrieve().body(JsonNode.class)
                .get("Text").asText();
        assertTrue(text.contains("2 x Handcrafted Bamboo Basket"), text);
        assertTrue(text.contains("₹1,359.98"), text);
        assertTrue(text.contains("Ahwa, Gujarat 394710"), text);
    }
}
