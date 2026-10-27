package com.vanmool.apigateway;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The whole path of an ended token, against the Kafka and Redis started by docker compose: a TokenRevoked event on
// user-events, as user-auth-service publishes it, is read by the gateway, kept in Redis, and the token is then refused.
// The listener runs here with a consumer group of its own, so the real gateway's position in the topic is untouched.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRevocationIT {
    private static final ServiceStub CART = new ServiceStub("cart-service");

    private final String endedToken = UUID.randomUUID().toString();
    private final String activeToken = UUID.randomUUID().toString();
    private final String expiredToken = UUID.randomUUID().toString();

    @Autowired
    private WebTestClient client;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.cart-service[0].uri", CART::uri);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("spring.kafka.consumer.group-id", () -> "api-gateway-it-" + UUID.randomUUID());
    }

    @AfterAll
    static void stop() {
        CART.stop();
    }

    @AfterEach
    void removeKeys() {
        redis.delete("revoked-token:" + endedToken, "revoked-token:" + activeToken, "revoked-token:" + expiredToken).block();
    }

    private static String jwtWithId(String jti) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(("{\"jti\":\"" + jti + "\"}").getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    private void publishTokenRevoked(String tokenId, Instant expiresAt) throws Exception {
        String event = """
                { "eventId": "%s", "eventType": "TokenRevoked", "occurredAt": "%s",
                  "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c", "tokenId": "%s", "expiresAt": "%s" }"""
                .formatted(UUID.randomUUID(), Instant.now(), tokenId, expiresAt);
        kafka.send("user-events", "a8c1e6ff-556d-4dd2-a238-148b3fca351c", event).get();
    }

    // The listener has to join its group and read the topic first: wait up to 30 s
    private boolean awaitKey(String tokenId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (Boolean.TRUE.equals(redis.hasKey("revoked-token:" + tokenId).block())) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    @Test
    void aTokenEndedByLogout_isRefused_untilItWouldHaveExpired_whileOtherTokensPass() throws Exception {
        // Arrange: user-auth-service's topic exists (created if this Kafka has never seen user-auth-service)
        NewTopic userEvents = TopicBuilder.name("user-events").partitions(3).replicas(1).build();
        kafkaAdmin.createOrModifyTopics(userEvents);
        Instant expiresAt = Instant.now().plus(Duration.ofHours(8));

        // Act: the logout's event, and one for a token that has already expired
        publishTokenRevoked(expiredToken, Instant.now().minus(Duration.ofMinutes(1)));
        publishTokenRevoked(endedToken, expiresAt);

        // Assert: the ended token is kept until its expiry, the expired one is not kept at all
        assertTrue(awaitKey(endedToken), "the TokenRevoked event was not read");
        long secondsLeft = redis.getExpire("revoked-token:" + endedToken).block().toSeconds();
        assertTrue(secondsLeft > 7 * 3600 && secondsLeft <= 8 * 3600, "kept for " + secondsLeft + " s");
        assertFalse(redis.hasKey("revoked-token:" + expiredToken).block());

        // The ended token is refused at the gateway, before cart-service; another token passes
        int callsBefore = CART.calls.get();
        client.get().uri("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtWithId(endedToken)).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_TOKEN")
                .jsonPath("$.message").isEqualTo("The token has been revoked");
        assertEquals(callsBefore, CART.calls.get());
        client.get().uri("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtWithId(activeToken)).exchange()
                .expectStatus().isOk();
    }
}
