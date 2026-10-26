package com.vanmool.apigateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Redis unreachable (a port where nothing listens): the rate limiter cannot count, and lets requests through instead
// of refusing everyone's sign-in. The gateway also stays healthy.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.data.redis.port=1")
class GatewayRateLimitWithoutRedisIT {
    private static final ServiceStub USER_AUTH = new ServiceStub("user-auth-service");

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-auth-service[0].uri", USER_AUTH::uri);
    }

    @AfterAll
    static void stop() {
        USER_AUTH.stop();
    }

    @Test
    void signInsGoThrough_beyondTheLimit_andTheGatewayStaysUp() {
        for (int i = 0; i < 12; i++) {
            client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isOk();
        }
        assertEquals(12, USER_AUTH.calls.get());
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
