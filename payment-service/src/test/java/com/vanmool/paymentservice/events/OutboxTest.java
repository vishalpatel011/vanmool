package com.vanmool.paymentservice.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vanmool.paymentservice.models.OutboxEvent;
import com.vanmool.paymentservice.repositories.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit tests of the outbox: what OutboxWriter stores, and how OutboxRelay sends it. The repository and KafkaTemplate
// are mocks; PaymentFlowIT shows the rows being written in the same transactions as the payment changes.
@ExtendWith(MockitoExtension.class)
class OutboxTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    // Configured like Spring Boot's ObjectMapper, which the application uses: dates as ISO-8601 text, not numbers
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private OutboxEvent pending(String eventType) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTopic("payment-events");
        event.setMessageKey(USER_ID.toString());
        event.setEventType(eventType);
        event.setPayload(objectMapper.createObjectNode().put("eventType", eventType));
        event.setCreatedAt(NOW);
        return event;
    }

    @Test
    void writer_storesTheEventAsFlatJson_underItsOwnEventId() throws Exception {
        // Arrange
        OutboxWriter writer = new OutboxWriter(outboxEventRepository, objectMapper, CLOCK);
        UUID orderId = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        PaymentFailedEvent event = new PaymentFailedEvent(UUID.randomUUID(), PaymentFailedEvent.TYPE, NOW,
                UUID.fromString("3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10"), orderId, USER_ID, "asha@example.com",
                new BigDecimal("999.98"), "EXPIRED");
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);

        // Act
        writer.add("payment-events", orderId.toString(), event);

        // Assert: the payload is compared as the JSON text the relay sends to Kafka
        verify(outboxEventRepository).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertEquals(event.eventId(), row.getId());
        assertEquals(orderId.toString(), row.getMessageKey());
        assertEquals("PaymentFailed", row.getEventType());
        assertNull(row.getPublishedAt());
        assertEquals(objectMapper.readTree("""
                { "eventId": "%s", "eventType": "PaymentFailed", "occurredAt": "2026-10-25T10:00:00Z",
                  "paymentId": "3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10", "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                  "userId": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10", "customerEmail": "asha@example.com",
                  "amount": 999.98, "reason": "EXPIRED" }"""
                .formatted(event.eventId())), objectMapper.readTree(row.getPayload().toString()));
    }

    @Test
    void relay_sendsPendingEventsOldestFirst_andMarksEachOneAfterKafkaConfirmedIt() {
        // Arrange
        OutboxEvent first = pending("PaymentSucceeded");
        OutboxEvent second = pending("PaymentSucceeded");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, CLOCK, Duration.ofDays(7));

        // Act
        relay.publishPending();

        // Assert
        InOrder order = inOrder(kafkaTemplate, outboxEventRepository);
        order.verify(kafkaTemplate).send("payment-events", USER_ID.toString(), "{\"eventType\":\"PaymentSucceeded\"}");
        order.verify(outboxEventRepository).markPublished(first.getId(), NOW);
        order.verify(kafkaTemplate).send("payment-events", USER_ID.toString(), "{\"eventType\":\"PaymentSucceeded\"}");
        order.verify(outboxEventRepository).markPublished(second.getId(), NOW);
    }

    @Test
    void relay_stopsAtTheFirstEventKafkaDoesNotAccept_andLeavesItAndTheRestForTheNextRound() {
        // Arrange: Kafka is down
        OutboxEvent first = pending("PaymentSucceeded");
        OutboxEvent second = pending("PaymentSucceeded");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Topic payment-events not present in metadata")));
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, CLOCK, Duration.ofDays(7));

        // Act
        relay.publishPending();

        // Assert: one attempt, nothing marked, so the order of events is kept
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void housekeeping_deletesEventsPublishedLongerAgoThanTheRetention() {
        // Arrange
        when(outboxEventRepository.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, CLOCK, Duration.ofDays(7));

        // Act
        relay.deleteOldPublished();

        // Assert
        verify(outboxEventRepository).deletePublishedBefore(NOW.minus(Duration.ofDays(7)));
    }
}
