package com.vanmool.notificationservice.emails;

// One plain-text email, ready to send
public record Email(String to, String subject, String body) {
}
