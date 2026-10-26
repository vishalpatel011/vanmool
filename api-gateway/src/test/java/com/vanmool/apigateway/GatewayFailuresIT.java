package com.vanmool.apigateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

// What the gateway answers when a service cannot: an instance that crashed but is still listed, a service with no
// instance at all, and one that does not answer in time. The answer timeout is 1 s here instead of 10 s.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s")
class GatewayFailuresIT {
    private static final ServiceStub LIVE_PRODUCT = new ServiceStub("product-service");
    private static final ServiceStub SLOW_ORDER = new ServiceStub("order-service", Duration.ofSeconds(3));

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        // product-service: a crashed instance still in the registry, and a live one
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", ServiceStub::deadInstance);
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[1].uri", LIVE_PRODUCT::uri);
        // cart-service: only a crashed instance
        registry.add("spring.cloud.discovery.client.simple.instances.cart-service[0].uri", ServiceStub::deadInstance);
        // order-service: slower than the answer timeout. payment-service: no instance at all
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri", SLOW_ORDER::uri);
    }

    @AfterAll
    static void stop() {
        LIVE_PRODUCT.stop();
        SLOW_ORDER.stop();
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
