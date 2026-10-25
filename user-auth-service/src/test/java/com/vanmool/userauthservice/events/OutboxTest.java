package com.vanmool.userauthservice.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vanmool.userauthservice.models.OutboxEvent;
import com.vanmool.userauthservice.repositories.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

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
// are mocks; OutboxEventsIT shows the same path against MySQL and the real Kafka broker.
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
        event.setTopic("user-events");
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
        UserRegisteredEvent event = UserRegisteredEvent.of(USER_ID, "Asha Gamit", "asha@example.com", NOW);
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);

        // Act
        writer.add("user-events", USER_ID.toString(), event);

        // Assert
        verify(outboxEventRepository).save(saved.capture());
        OutboxEvent row = saved.getValue();
        assertEquals(event.eventId(), row.getId());
        assertEquals("user-events", row.getTopic());
        assertEquals(USER_ID.toString(), row.getMessageKey());
        assertEquals("UserRegistered", row.getEventType());
        assertNull(row.getPublishedAt());
        assertEquals(objectMapper.readTree("""
                { "eventId": "%s", "eventType": "UserRegistered", "occurredAt": "2026-10-25T10:00:00Z",
                  "userId": "5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10", "name": "Asha Gamit", "email": "asha@example.com" }"""
                .formatted(event.eventId())), row.getPayload());
    }

    @Test
    void relay_sendsPendingEventsOldestFirst_andMarksEachOneAfterKafkaConfirmedIt() {
        // Arrange
        OutboxEvent first = pending("UserRegistered");
        OutboxEvent second = pending("UserRegistered");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, CLOCK, Duration.ofDays(7));

        // Act
        relay.publishPending();

        // Assert
        InOrder order = inOrder(kafkaTemplate, outboxEventRepository);
        order.verify(kafkaTemplate).send("user-events", USER_ID.toString(), "{\"eventType\":\"UserRegistered\"}");
        order.verify(outboxEventRepository).save(first);
        order.verify(kafkaTemplate).send("user-events", USER_ID.toString(), "{\"eventType\":\"UserRegistered\"}");
        order.verify(outboxEventRepository).save(second);
        assertEquals(NOW, first.getPublishedAt());
        assertEquals(NOW, second.getPublishedAt());
    }

    @Test
    void relay_stopsAtTheFirstEventKafkaDoesNotAccept_andLeavesItAndTheRestForTheNextRound() {
        // Arrange: Kafka is down
        OutboxEvent first = pending("UserRegistered");
        OutboxEvent second = pending("UserRegistered");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Topic user-events not present in metadata")));
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
