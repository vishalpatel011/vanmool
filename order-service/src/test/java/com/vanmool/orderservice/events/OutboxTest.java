package com.vanmool.orderservice.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vanmool.orderservice.models.OutboxEvent;
import com.vanmool.orderservice.repositories.OutboxEventRepository;
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
// are mocks; OrderFlowIT shows the rows being written in the same transactions as the order changes.
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
        event.setTopic("order-events");
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
        OrderCancelledEvent event = new OrderCancelledEvent(UUID.randomUUID(), OrderCancelledEvent.TYPE, NOW, orderId,
                USER_ID, "asha@example.com", new BigDecimal("999.98"));
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);

        // Act
        writer.add("order-events", orderId.toString(), event);

        // Assert: the payload is compared as the JSON text the relay sends to Kafka
        verify(outboxEventRepository).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertEquals(event.eventId(), row.getId());
        assertEquals("order-events", row.getTopic());
        assertEquals(orderId.toString(), row.getMessageKey());
        assertEquals("OrderCancelled", row.getEventType());
        assertNull(row.getPublishedAt());
        assertEquals(objectMapper.readTree("""
                { "eventId": "%s", "eventType": "OrderCancelled", "occurredAt": "2026-10-25T10:00:00Z",
                  "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "userId": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10",
                  "customerEmail": "asha@example.com", "totalAmount": 999.98 }"""
                .formatted(event.eventId())), objectMapper.readTree(row.getPayload().toString()));
    }

    @Test
    void relay_sendsPendingEventsOldestFirst_andMarksEachOneAfterKafkaConfirmedIt() {
        // Arrange
        OutboxEvent first = pending("OrderPlaced");
        OutboxEvent second = pending("OrderPlaced");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, CLOCK, Duration.ofDays(7));

        // Act
        relay.publishPending();

        // Assert
        InOrder order = inOrder(kafkaTemplate, outboxEventRepository);
        order.verify(kafkaTemplate).send("order-events", USER_ID.toString(), "{\"eventType\":\"OrderPlaced\"}");
        order.verify(outboxEventRepository).save(first);
        order.verify(kafkaTemplate).send("order-events", USER_ID.toString(), "{\"eventType\":\"OrderPlaced\"}");
        order.verify(outboxEventRepository).save(second);
        assertEquals(NOW, first.getPublishedAt());
        assertEquals(NOW, second.getPublishedAt());
    }

    @Test
    void relay_stopsAtTheFirstEventKafkaDoesNotAccept_andLeavesItAndTheRestForTheNextRound() {
        // Arrange: Kafka is down
        OutboxEvent first = pending("OrderPlaced");
        OutboxEvent second = pending("OrderPlaced");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Topic order-events not present in metadata")));
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, CLOCK, Duration.ofDays(7));

        // Act
        relay.publishPending();

        // Assert: one attempt, nothing marked, so the order of events is kept
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).save(any());
        assertNull(first.getPublishedAt());
        assertNull(second.getPublishedAt());
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
