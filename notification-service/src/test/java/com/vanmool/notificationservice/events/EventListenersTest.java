package com.vanmool.notificationservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vanmool.notificationservice.emails.Email;
import com.vanmool.notificationservice.emails.EmailComposer;
import com.vanmool.notificationservice.emails.EmailSender;
import com.vanmool.notificationservice.services.HandledEvents;
import com.vanmool.notificationservice.services.Notifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The listeners and the Notifier together, with the real EmailComposer; the mail server (EmailSender) and the Redis
// record of handled events (HandledEvents) are mocks. The messages are the JSON the producers really publish.
@ExtendWith(MockitoExtension.class)
class EventListenersTest {
    private static final UUID EVENT_ID = UUID.fromString("0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10");
    private static final String USER_REGISTERED = """
            { "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "UserRegistered",
              "occurredAt": "2026-10-25T10:00:00Z", "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c",
              "name": "Asha Gamit", "email": "asha@example.com" }""";
    private static final String ORDER_PLACED = """
            { "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "OrderPlaced", "occurredAt": "2026-10-25T10:00:00Z",
              "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c",
              "customerEmail": "asha@example.com",
              "items": [ { "productId": "150dac81-6130-4903-9385-9b682db879b3", "name": "Handcrafted Bamboo Basket",
                           "unitPrice": 499.99, "quantity": 2 } ],
              "totalAmount": 999.98,
              "shippingAddress": { "name": "Asha Gamit", "phone": "9876543210", "line1": "12 Station Road", "line2": null,
                                   "city": "Ahwa", "state": "Gujarat", "pincode": "394710" } }""";

    @Mock
    private HandledEvents handledEvents;

    @Mock
    private EmailSender emailSender;

    private EventListeners listeners;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        listeners = new EventListeners(objectMapper, new EmailComposer(), new Notifier(handledEvents, emailSender));
    }

    private Email sentEmail() {
        ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
        verify(emailSender).send(email.capture());
        return email.getValue();
    }

    @Test
    void userRegistered_sendsTheWelcomeEmail_andRecordsTheEvent() throws Exception {
        // Act
        listeners.onUserEvent(USER_REGISTERED);

        // Assert: recorded only after the email went out
        assertEquals("Welcome to Vanmool", sentEmail().subject());
        verify(handledEvents).add(EVENT_ID);
    }

    @Test
    void orderPlaced_sendsTheConfirmation() throws Exception {
        // Act
        listeners.onOrderEvent(ORDER_PLACED);

        // Assert
        Email email = sentEmail();
        assertEquals("asha@example.com", email.to());
        assertEquals("Your Vanmool order 7c9e6679 is placed", email.subject());
    }

    @Test
    void orderCancelled_sendsTheCancellation() throws Exception {
        // Act
        listeners.onOrderEvent("""
                { "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "OrderCancelled",
                  "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "customerEmail": "asha@example.com", "totalAmount": 999.98 }""");

        // Assert
        assertEquals("Your Vanmool order 7c9e6679 is cancelled", sentEmail().subject());
    }

    @Test
    void anEventHandledBefore_sendsNoSecondEmail() throws Exception {
        // Arrange: Kafka delivered the same event again
        when(handledEvents.contains(EVENT_ID)).thenReturn(true);

        // Act
        listeners.onUserEvent(USER_REGISTERED);

        // Assert
        verifyNoInteractions(emailSender);
        verify(handledEvents, never()).add(any());
    }

    @Test
    void mailServerFailing_isPassedOnForARetry_andTheEventIsNotRecorded() {
        // Arrange
        doThrow(new MailSendException("Connection refused: localhost:1025")).when(emailSender).send(any());

        // Act & Assert: the Kafka error handler retries, then parks the event on the dead-letter topic
        assertThrows(MailSendException.class, () -> listeners.onUserEvent(USER_REGISTERED));
        verify(handledEvents, never()).add(any());
    }

    @Test
    void orderWithoutAnEmailAddress_isSkipped() throws Exception {
        // Act
        listeners.onOrderEvent(ORDER_PLACED.replace("\"customerEmail\": \"asha@example.com\"", "\"customerEmail\": null"));

        // Assert
        verifyNoInteractions(emailSender);
    }

    @Test
    void eventsThatNeedNoEmail_areIgnored() throws Exception {
        // Act
        listeners.onUserEvent("{ \"eventId\": \"0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10\", \"eventType\": \"UserDeleted\" }");
        listeners.onOrderEvent("{ \"eventId\": \"0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10\", \"eventType\": \"OrderShipped\" }");

        // Assert
        verifyNoInteractions(emailSender, handledEvents);
    }

    @Test
    void messagesThatCanNeverWork_areRefusedAsNotRetryable() {
        // Not JSON; no eventId; an OrderPlaced without its order details
        assertThrows(JsonProcessingException.class, () -> listeners.onOrderEvent("not json"));
        assertThrows(MalformedEventException.class,
                () -> listeners.onUserEvent("{ \"eventType\": \"UserRegistered\", \"email\": \"asha@example.com\" }"));
        assertThrows(MalformedEventException.class, () -> listeners.onOrderEvent(
                "{ \"eventId\": \"0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10\", \"eventType\": \"OrderPlaced\" }"));
        verifyNoInteractions(emailSender);
    }
}
