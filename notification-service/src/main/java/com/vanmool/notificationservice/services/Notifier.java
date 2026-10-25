package com.vanmool.notificationservice.services;

import com.vanmool.notificationservice.emails.Email;
import com.vanmool.notificationservice.emails.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Sends the email for an event once: skipped if this event was handled before, recorded after a successful send.
// If the send fails, nothing is recorded and the exception goes back to Kafka's error handler, which tries again.
// A crash between sending and recording would send the email once more when the event is redelivered: delivery
// stays at least once, and a duplicate email is the rare price.
@Slf4j
@Service
public class Notifier {
    private final HandledEvents handledEvents;
    private final EmailSender emailSender;

    public Notifier(HandledEvents handledEvents, EmailSender emailSender) {
        this.handledEvents = handledEvents;
        this.emailSender = emailSender;
    }

    public void sendOnce(UUID eventId, Email email) {
        if (email.to() == null || email.to().isBlank()) {
            // For example an order placed with a token that had no email claim; nobody to write to
            log.warn("Event {} has no email address; no email sent", eventId);
            return;
        }
        if (handledEvents.contains(eventId)) {
            log.info("Event {} was already handled; no second email", eventId);
            return;
        }
        emailSender.send(email);
        handledEvents.add(eventId);
        log.info("Sent \"{}\" for event {}", email.subject(), eventId);
    }
}
