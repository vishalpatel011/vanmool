package com.vanmool.notificationservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.notificationservice.emails.EmailComposer;
import com.vanmool.notificationservice.services.Notifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Reads the events that need an email. Each event's JSON has an eventType; types this service does not need are
// skipped, so producers can add new events without breaking it.
@Component
public class EventListeners {
    private final ObjectMapper objectMapper;
    private final EmailComposer emailComposer;
    private final Notifier notifier;

    public EventListeners(ObjectMapper objectMapper, EmailComposer emailComposer, Notifier notifier) {
        this.objectMapper = objectMapper;
        this.emailComposer = emailComposer;
        this.notifier = notifier;
    }

    @KafkaListener(topics = "user-events")
    public void onUserEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        switch (event.path("eventType").asText()) {
            case "UserRegistered" -> {
                Events.UserRegistered registered = read(event, Events.UserRegistered.class);
                notifier.sendOnce(registered.eventId(), emailComposer.welcome(registered));
            }
            case "PasswordChanged" -> {
                Events.PasswordChanged changed = read(event, Events.PasswordChanged.class);
                notifier.sendOnce(changed.eventId(), emailComposer.passwordChanged(changed));
            }
            default -> {
                // not an event that needs an email
            }
        }
    }

    @KafkaListener(topics = "order-events")
    public void onOrderEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        switch (event.path("eventType").asText()) {
            case "OrderPlaced" -> {
                Events.OrderPlaced placed = read(event, Events.OrderPlaced.class);
                if (placed.orderId() == null || placed.items() == null || placed.totalAmount() == null
                        || placed.shippingAddress() == null) {
                    throw new MalformedEventException("OrderPlaced event without its order details: " + event);
                }
                notifier.sendOnce(placed.eventId(), emailComposer.orderConfirmation(placed));
            }
            case "OrderCancelled" -> {
                Events.OrderCancelled cancelled = read(event, Events.OrderCancelled.class);
                if (cancelled.orderId() == null || cancelled.totalAmount() == null) {
                    throw new MalformedEventException("OrderCancelled event without its order details: " + event);
                }
                notifier.sendOnce(cancelled.eventId(), emailComposer.orderCancellation(cancelled));
            }
            case "OrderShipped" -> {
                Events.OrderShipped shipped = read(event, Events.OrderShipped.class);
                if (shipped.orderId() == null || shipped.carrier() == null) {
                    throw new MalformedEventException("OrderShipped event without its order or carrier: " + event);
                }
                notifier.sendOnce(shipped.eventId(), emailComposer.orderShipped(shipped));
            }
            case "OrderDelivered" -> {
                Events.OrderDelivered delivered = read(event, Events.OrderDelivered.class);
                if (delivered.orderId() == null) {
                    throw new MalformedEventException("OrderDelivered event without its orderId: " + event);
                }
                notifier.sendOnce(delivered.eventId(), emailComposer.orderDelivered(delivered));
            }
            default -> {
                // not an event that needs an email
            }
        }
    }

    @KafkaListener(topics = "payment-events")
    public void onPaymentEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        switch (event.path("eventType").asText()) {
            case "PaymentSucceeded" -> {
                Events.PaymentSucceeded paid = read(event, Events.PaymentSucceeded.class);
                if (paid.orderId() == null || paid.amount() == null) {
                    throw new MalformedEventException("PaymentSucceeded event without its payment details: " + event);
                }
                notifier.sendOnce(paid.eventId(), emailComposer.paymentReceipt(paid));
            }
            case "PaymentFailed" -> {
                Events.PaymentFailed failed = read(event, Events.PaymentFailed.class);
                if (failed.orderId() == null || failed.amount() == null) {
                    throw new MalformedEventException("PaymentFailed event without its payment details: " + event);
                }
                notifier.sendOnce(failed.eventId(), emailComposer.paymentFailed(failed));
            }
            default -> {
                // not an event that needs an email
            }
        }
    }

    // Every event needs its eventId: without it, a repeat could not be recognised
    private <T> T read(JsonNode event, Class<T> type) throws JsonProcessingException {
        if (!event.hasNonNull("eventId")) {
            throw new MalformedEventException("Event without an eventId: " + event);
        }
        return objectMapper.treeToValue(event, type);
    }
}
