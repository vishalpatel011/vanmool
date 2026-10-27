package com.vanmool.apigateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// What the gateway answers when a service cannot: an instance that crashed but is still listed, one that closes the
// connection without answering, a service with no instance at all, and one that does not answer in time. The answer
// timeout is 1 s here instead of 10 s.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s")
class GatewayFailuresIT {
    private static final ServiceStub LIVE_PRODUCT = new ServiceStub("product-service");
    private static final ServiceStub SLOW_ORDER = new ServiceStub("order-service", Duration.ofSeconds(3));
    private static final ServiceStub.ClosesWithoutAnswering CLOSING_USER_AUTH = new ServiceStub.ClosesWithoutAnswering();
    private static final ServiceStub LIVE_USER_AUTH = new ServiceStub("user-auth-service");

    @Autowired
    private WebTestClient client;

    @Autowired
    private HttpClientProperties httpClient;

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        // product-service: a crashed instance still in the registry, and a live one
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", ServiceStub::deadInstance);
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[1].uri", LIVE_PRODUCT::uri);
        // cart-service: only a crashed instance
        registry.add("spring.cloud.discovery.client.simple.instances.cart-service[0].uri", ServiceStub::deadInstance);
        // order-service: slower than the answer timeout. payment-service: no instance at all
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri", SLOW_ORDER::uri);
        // user-auth-service: an instance that closes every connection without answering, and a live one
        registry.add("spring.cloud.discovery.client.simple.instances.user-auth-service[0].uri", CLOSING_USER_AUTH::uri);
        registry.add("spring.cloud.discovery.client.simple.instances.user-auth-service[1].uri", LIVE_USER_AUTH::uri);
    }

    @AfterAll
    static void stop() {
        LIVE_PRODUCT.stop();
        SLOW_ORDER.stop();
        CLOSING_USER_AUTH.stop();
        LIVE_USER_AUTH.stop();
    }

    @Test
    void readsThatPickACrashedInstance_areRetriedOnTheLiveOne() {
        for (int i = 0; i < 6; i++) {
            client.get().uri("/products/150dac81-6130-4903-9385-9b682db879b3").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("product-service");
        }
        assertEquals(6, LIVE_PRODUCT.calls.get());
    }

    @Test
    void readsThatGetTheConnectionClosedWithoutAnAnswer_areRetriedOnTheNextInstance() {
        for (int i = 0; i < 6; i++) {
            client.get().uri("/users/me").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.service").isEqualTo("user-auth-service");
        }
        // Round robin picked the closing instance for some of them; those were sent again to the live one
        assertTrue(CLOSING_USER_AUTH.calls.get() > 0);
        assertEquals(6, LIVE_USER_AUTH.calls.get());
    }

    @Test
    void idleConnections_areClosedByTheGateway_beforeTheServicesCloseThem() {
        // Tomcat closes a kept-alive connection after 60 s; the gateway stops reusing one after 15 s
        assertEquals(Duration.ofSeconds(15), httpClient.getPool().getMaxIdleTime());
    }

    @Test
    void aWriteThatGetsTheConnectionClosedWithoutAnAnswer_is502_notRetried() {
        // Two writes in a row: round robin sends one of them to the closing instance
        int closingBefore = CLOSING_USER_AUTH.calls.get();
        List<EntityExchangeResult<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(client.post().uri("/auth/logout").exchange().expectBody(String.class).returnResult());
        }

        // One answered by the live instance, one 502; the write reached the closing instance once, it was not retried
        assertEquals(List.of(200, 502), results.stream().map(result -> result.getStatus().value()).sorted().toList());
        assertEquals(closingBefore + 1, CLOSING_USER_AUTH.calls.get());
        String body = results.stream().filter(result -> result.getStatus().value() == 502).findFirst().orElseThrow()
                .getResponseBody();
        assertTrue(body.contains("\"error\":\"BAD_GATEWAY\""), body);
        assertTrue(body.contains("closed the connection without answering"), body);
    }

    @Test
    void aServiceWhoseOnlyInstanceCrashed_is503() {
        // A read is retried, but on the same dead instance; a write is not retried at all
        client.get().uri("/cart").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("The service for this request is not available right now")
                .jsonPath("$.path").isEqualTo("/cart");
        client.post().uri("/cart/items").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void aServiceWithNoInstanceAtAll_is503() {
        client.post().uri("/payments").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void aServiceThatDoesNotAnswerInTime_is504() {
        client.get().uri("/orders").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.error").isEqualTo("GATEWAY_TIMEOUT")
                .jsonPath("$.message").isEqualTo("The service for this request did not answer in time");
    }
}
