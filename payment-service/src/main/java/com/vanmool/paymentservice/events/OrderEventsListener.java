package com.vanmool.paymentservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.paymentservice.services.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Reads order-service's events: when an order is cancelled, its open payment link is closed
@Component
public class OrderEventsListener {
    private final ObjectMapper objectMapper;
    private final PaymentService paymentService;

    public OrderEventsListener(ObjectMapper objectMapper, PaymentService paymentService) {
        this.objectMapper = objectMapper;
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = "order-events")
    public void onOrderEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        if (!"OrderCancelled".equals(event.path("eventType").asText())) {
            return;
        }
        try {
            paymentService.closeOpenPayment(UUID.fromString(event.path("orderId").asText()));
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("OrderCancelled event without a valid orderId: " + event);
        }
    }
}
