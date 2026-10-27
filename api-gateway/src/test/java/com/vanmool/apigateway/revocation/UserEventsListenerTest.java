package com.vanmool.apigateway.revocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserEventsListenerTest {
    private final RevokedTokens revokedTokens = mock(RevokedTokens.class);
    private final UserEventsListener listener = new UserEventsListener(new ObjectMapper(), revokedTokens);

    @Test
    void tokenRevoked_isRemembered_untilTheTokenWouldHaveExpired() throws Exception {
        when(revokedTokens.remember(any(), any())).thenReturn(Mono.just(true));

        // The JSON user-auth-service publishes
        listener.onUserEvent("""
                { "eventId": "1c7f4d2f-9b1e-4a8f-8b62-4d3b7f9e0a21", "eventType": "TokenRevoked",
                  "occurredAt": "2026-10-27T10:00:00Z", "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c",
                  "tokenId": "0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71", "expiresAt": "2026-10-27T20:00:00Z" }""");

        verify(revokedTokens).remember("0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71", Instant.parse("2026-10-27T20:00:00Z"));
    }

    @Test
    void otherUserEvents_areNotTheGatewaysBusiness() throws Exception {
        listener.onUserEvent("{ \"eventId\": \"x\", \"eventType\": \"UserRegistered\", \"email\": \"asha@example.com\" }");

        verifyNoInteractions(revokedTokens);
    }

    @Test
    void eventsThatCanNeverWork_areRefused_forTheDeadLetterTopic() {
        assertThrows(JsonProcessingException.class, () -> listener.onUserEvent("not json"));
        assertThrows(MalformedEventException.class, () -> listener.onUserEvent(
                "{ \"eventType\": \"TokenRevoked\", \"expiresAt\": \"2026-10-27T20:00:00Z\" }"));
        assertThrows(MalformedEventException.class, () -> listener.onUserEvent(
                "{ \"eventType\": \"TokenRevoked\", \"tokenId\": \"0f6e2b8a\", \"expiresAt\": \"tomorrow\" }"));
        verifyNoInteractions(revokedTokens);
    }
}
