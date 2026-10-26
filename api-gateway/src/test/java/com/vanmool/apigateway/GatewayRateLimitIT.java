package com.vanmool.apigateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The rate limit on signing in, against the Redis started by docker compose. Every request of the test comes from
// 127.0.0.1, so they share one budget; the counters are removed before and after each test.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRateLimitIT {
    private static final ServiceStub USER_AUTH = new ServiceStub("user-auth-service");
    private static final ServiceStub PRODUCT = new ServiceStub("product-service");

    @Autowired
    private WebTestClient client;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-auth-service[0].uri", USER_AUTH::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", PRODUCT::uri);
    }

    @AfterAll
    static void stop() {
        USER_AUTH.stop();
        PRODUCT.stop();
    }

    @BeforeEach
    @AfterEach
    void removeCounters() {
        redis.keys("request_rate_limiter.*").flatMap(redis::delete).blockLast();
    }

    private int signIn() {
        return client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\": \"asha@example.com\", \"password\": \"a guess\"}")
                .exchange().returnResult(String.class).getStatus().value();
    }

    @Test
    void theEleventhSignInWithinAMinute_isRefusedWith429_andNeverReachesTheService() {
        // Act
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            statuses.add(signIn());
        }

        // Assert
        assertEquals(List.of(200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 429), statuses);
        assertEquals(10, USER_AUTH.calls.get());
    }

    @Test
    void signUpAndPasswordReset_shareTheBudget_butOtherRequestsAreNotLimited() {
        // Use up the budget with sign-ups and reset requests
        for (int i = 0; i < 5; i++) {
            client.post().uri("/auth/signup").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
            client.post().uri("/auth/password-reset").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        }
        client.post().uri("/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(429);

        // The catalog, the profile and the page a reset link opens are not counted
        for (int i = 0; i < 15; i++) {
            client.get().uri("/products").exchange().expectStatus().isOk();
        }
        client.get().uri("/users/me").exchange().expectStatus().isOk();
        client.get().uri("/auth/password-reset/confirm?token=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").exchange().expectStatus().isOk();
    }

    @Test
    void theRemainingBudget_isInTheResponseHeaders() {
        client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "54")
                .expectHeader().valueEquals("X-RateLimit-Requested-Tokens", "6");
    }
}
