package com.vanmool.apigateway.revocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

// Reads user-auth-service's events and keeps the ended tokens (TokenRevoked). Other user events are not the gateway's
// business. Kafka may deliver an event twice: remembering a token again changes nothing. If Redis cannot be written,
// the error handler tries again, then parks the event on the dead-letter topic (RevocationConfig)
@Component
public class UserEventsListener {
    private final ObjectMapper objectMapper;
    private final RevokedTokens revokedTokens;

    public UserEventsListener(ObjectMapper objectMapper, RevokedTokens revokedTokens) {
        this.objectMapper = objectMapper;
        this.revokedTokens = revokedTokens;
    }

    @KafkaListener(topics = "user-events")
    public void onUserEvent(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        if (!"TokenRevoked".equals(event.path("eventType").asText())) {
            return;
        }
        String tokenId = event.path("tokenId").asText(null);
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(event.path("expiresAt").asText(""));
        } catch (DateTimeParseException e) {
            throw new MalformedEventException("TokenRevoked event without a valid expiresAt: " + event.path("eventId").asText());
        }
        if (tokenId == null || tokenId.isBlank()) {
            throw new MalformedEventException("TokenRevoked event without a tokenId: " + event.path("eventId").asText());
        }
        // A Kafka listener thread, not a web request: waiting for Redis here holds nobody up
        revokedTokens.remember(tokenId, expiresAt).block(Duration.ofSeconds(5));
    }
}
