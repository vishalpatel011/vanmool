package com.vanmool.productservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.productservice.services.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Reads order-service's events. When an order is cancelled, its reserved units go back to the stock.
// Kafka delivers each event at least once, so an event can arrive twice; release is idempotent (keyed by the order
// id), so a repeat changes nothing. If the release fails (MySQL down), the error handler retries, then parks the
// event on the dead-letter topic (KafkaConfig).
@Slf4j
@Component
public class OrderEventsListener {
    static final String ORDER_CANCELLED = "OrderCancelled";

    private final ObjectMapper objectMapper;
    private final StockService stockService;

    public OrderEventsListener(ObjectMapper objectMapper, StockService stockService) {
        this.objectMapper = objectMapper;
        this.stockService = stockService;
    }

    @KafkaListener(topics = "order-events")
    public void onOrderEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        // Other events on the topic (OrderPlaced) are for other services; this one only needs cancellations
        if (!ORDER_CANCELLED.equals(event.path("eventType").asText())) {
            return;
        }
        UUID orderId = orderId(event);
        stockService.release(orderId);
        log.info("Released the stock of cancelled order {} (event {})", orderId, event.path("eventId").asText());
    }

    private static UUID orderId(JsonNode event) {
        try {
            return UUID.fromString(event.path("orderId").asText());
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("OrderCancelled event without a valid orderId: " + event);
        }
    }
}
