package com.vanmool.apigateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The gateway on a free port with one stand-in instance per service. Eureka is off in tests; the instances of each
// service name are given as properties, as Eureka would give them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingIT {
    private static final Map<String, ServiceStub> SERVICES = Map.of(
            "user-auth-service", new ServiceStub("user-auth-service"),
            "product-service", new ServiceStub("product-service"),
            "cart-service", new ServiceStub("cart-service"),
            "order-service", new ServiceStub("order-service"),
            "payment-service", new ServiceStub("payment-service"));

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void serviceInstances(DynamicPropertyRegistry registry) {
        SERVICES.forEach((name, stub) ->
                registry.add("spring.cloud.discovery.client.simple.instances." + name + "[0].uri", stub::uri));
    }

    @AfterAll
    static void stop() {
        SERVICES.values().forEach(ServiceStub::stop);
    }

    // The access log line is written when the exchange completes, which can be a moment after the client has its
    // answer: wait up to 2 s for it
    private static void awaitLogLine(CapturedOutput output, String text) throws InterruptedException {
        for (int i = 0; i < 40 && !output.getOut().contains(text); i++) {
            Thread.sleep(50);
        }
        assertTrue(output.getOut().contains(text), output.getOut());
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "POST, /auth/login,                         user-auth-service",
            "GET,  /users/me,                           user-auth-service",
            "GET,  /products,                           product-service",
            "GET,  /products/150dac81-6130-4903-9385-9b682db879b3, product-service",
            "GET,  /categories,                         product-service",
            "GET,  /cart,                               cart-service",
            "POST, /cart/items,                         cart-service",
            "POST, /orders,                             order-service",
            "POST, /admin/orders/7c9e6679-7425-40de-944b-e07fc1f90ae7/ship, order-service",
            "POST, /payments,                           payment-service",
            "POST, /payments/webhooks/razorpay,         payment-service"
    })
    void eachPath_goesToTheServiceThatOwnsIt_unchanged(String method, String path, String service) {
        client.method(HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.method").isEqualTo(method)
                .jsonPath("$.path").isEqualTo(path);
    }

    @ParameterizedTest(name = "/api-docs/{0}")
    @CsvSource({"product-service", "user-auth-service", "cart-service", "order-service", "payment-service"})
    void eachServicesApiDescription_isFetchedFromItsV3ApiDocs(String service) {
        client.get().uri("/api-docs/" + service).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.path").isEqualTo("/v3/api-docs");
    }

    @Test
    void theSwaggerPage_isServedByTheGateway_andListsEveryService() {
        client.get().uri("/v3/api-docs/swagger-config").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[*].url").value(containsInAnyOrder("/api-docs/product-service",
                        "/api-docs/user-auth-service", "/api-docs/cart-service", "/api-docs/order-service",
                        "/api-docs/payment-service"));
        client.get().uri("/swagger-ui.html").exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value("Location", endsWith("/swagger-ui/index.html"));
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }

    @Test
    void theQueryIsPassedOn() {
        client.get().uri("/products?q=bamboo&sort=price,asc").exchange()
                .expectBody().jsonPath("$.query").isEqualTo("q=bamboo&sort=price,asc");
    }

    @ParameterizedTest
    @CsvSource({"/stock-reservations", "/stock-reservations/7c9e6679-7425-40de-944b-e07fc1f90ae7", "/oauth2/jwks", "/nothing-here"})
    void internalEndpoints_andUnknownPaths_are404_inTheServicesErrorFormat(String path) {
        client.post().uri(path).exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.error").isEqualTo("NOT_FOUND")
                .jsonPath("$.message").isEqualTo("There is no API at this path")
                .jsonPath("$.path").isEqualTo(path)
                .jsonPath("$.timestamp").isNotEmpty();
    }

    @Test
    void everyRequest_getsAnId_thatTheServiceAndTheClientBothSee(CapturedOutput output) throws InterruptedException {
        // Arrange & Act
        WebTestClient.ResponseSpec response = client.get().uri("/cart").exchange();

        // Assert: the id in the response header is the one the service received
        String id = response.expectHeader().value("X-Request-Id", value -> {}).returnResult(String.class)
                .getResponseHeaders().getFirst("X-Request-Id");
        response.expectBody().jsonPath("$.requestId").isEqualTo(id);
        assertTrue(id.matches("[0-9a-f-]{36}"), id);
        // and the access log line names the route, the status and the id
        awaitLogLine(output, "GET /cart -> cart-service 200 in ");
        assertTrue(output.getOut().contains("[" + id + "]"), output.getOut());
    }

    @Test
    void theClientsOwnId_isKept_andA404AlsoCarriesAnId() {
        client.get().uri("/orders").header("X-Request-Id", "support-ticket-42").exchange()
                .expectHeader().valueEquals("X-Request-Id", "support-ticket-42")
                .expectBody().jsonPath("$.requestId").isEqualTo("support-ticket-42");
        client.get().uri("/nothing-here").exchange()
                .expectHeader().value("X-Request-Id", matchesPattern("[0-9a-f-]{36}"));
    }

    @Test
    void theAccessLog_leavesTheQueryOut(CapturedOutput output) throws InterruptedException {
        // A password reset link carries its token in the query
        client.get().uri("/auth/password-reset/confirm?token=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").exchange()
                .expectStatus().isOk();

        awaitLogLine(output, "GET /auth/password-reset/confirm -> user-auth-service 200");
        assertFalse(output.getOut().contains("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"), "the token was logged");
    }
}
