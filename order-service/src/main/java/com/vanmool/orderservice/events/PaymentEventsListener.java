package com.vanmool.orderservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.orderservice.services.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Reads payment-service's events (HLD: "once the payment is confirmed, it produces a message on Kafka to notify the
// Order Management Service"). PaymentSucceeded confirms the order. PaymentFailed changes nothing: the order stays
// PENDING_PAYMENT and the customer can try again until the payment window ends.
@Slf4j
@Component
public class PaymentEventsListener {
    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    public PaymentEventsListener(ObjectMapper objectMapper, OrderService orderService) {
        this.objectMapper = objectMapper;
        this.orderService = orderService;
    }

    @KafkaListener(topics = "payment-events")
    public void onPaymentEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        switch (event.path("eventType").asText()) {
            case "PaymentSucceeded" -> orderService.confirmPayment(uuid(event, "orderId"), uuid(event, "paymentId"));
            case "PaymentFailed" -> log.info("Payment {} for order {} ended {}; the order stays open for another try",
                    event.path("paymentId").asText(), event.path("orderId").asText(), event.path("reason").asText());
            default -> {
                // not for this service
            }
        }
    }

    private static UUID uuid(JsonNode event, String field) {
        try {
            return UUID.fromString(event.path(field).asText());
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("Payment event without a valid " + field + ": " + event);
        }
    }
}
