package com.vanmool.cartservice.clients;

import com.vanmool.cartservice.configs.ProductClientConfig;
import com.vanmool.cartservice.exceptions.ProductNotFoundException;
import com.vanmool.cartservice.exceptions.ProductServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Tests the call to product-service. MockRestServiceServer answers in place of product-service, so the responses are
// exact and no server is needed. The two timeout tests use a real socket on this machine instead, because a timeout
// only happens on a real connection.
class ProductClientTest {
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final String PRODUCT_URL = "http://product-service/products/" + BASKET_ID;

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://product-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ProductClient productClient = new ProductClient(builder.build());

    @Test
    void existingProduct_readsTheFieldsTheCartNeeds_andIgnoresTheRest() {
        // Arrange: the whole JSON of product-service's GET /products/{id}
        server.expect(requestTo(PRODUCT_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "150dac81-6130-4903-9385-9b682db879b3",
                          "name": "Handcrafted Bamboo Basket",
                          "price": 499.99,
                          "description": "Woven by hand from local bamboo in Dangs, Gujarat",
                          "imageUrl": "https://example.com/images/bamboo-basket.jpg",
                          "category": "Traditional Crafts",
                          "originRegion": "Dangs, Gujarat",
                          "materials": "Bamboo",
                          "story": "Woven by hand by tribal artisans in the Dangs district"
                        }""", MediaType.APPLICATION_JSON));

        // Act
        ProductDetails product = productClient.getProduct(BASKET_ID);

        // Assert
        assertEquals(new ProductDetails(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"),
                "https://example.com/images/bamboo-basket.jpg"), product);
        server.verify();
    }

    @Test
    void notFound_becomesProductNotFound() {
        // Arrange
        server.expect(requestTo(PRODUCT_URL)).andRespond(withResourceNotFound());

        // Act & Assert
        ProductNotFoundException exception = assertThrows(ProductNotFoundException.class,
                () -> productClient.getProduct(BASKET_ID));
        assertTrue(exception.getMessage().contains(BASKET_ID.toString()));
    }

    @Test
    void serverError_becomesUnavailable() {
        // Arrange
        server.expect(requestTo(PRODUCT_URL)).andRespond(withServerError());

        // Act & Assert
        assertThrows(ProductServiceUnavailableException.class, () -> productClient.getProduct(BASKET_ID));
    }

    // The real RestClient of ProductClientConfig, with short timeouts, against a port on this machine
    private static ProductClient clientWithTimeouts(int port, Duration readTimeout) {
        RestClient restClient = new ProductClientConfig().productRestClient(RestClient.builder(),
                "http://127.0.0.1:" + port, Duration.ofMillis(500), readTimeout);
        return new ProductClient(restClient);
    }

    @Test
    void nothingListening_becomesUnavailable() throws IOException {
        // Arrange: take a free port and close it again, so the connection is refused
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        // Act & Assert
        assertThrows(ProductServiceUnavailableException.class,
                () -> clientWithTimeouts(port, Duration.ofMillis(300)).getProduct(BASKET_ID));
    }

    @Test
    void noAnswerWithinTheReadTimeout_becomesUnavailable_withoutWaitingLonger() throws IOException {
        // Arrange: a server that accepts the connection and then never answers, like a product-service that hangs
        try (ServerSocket silentServer = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = silentServer.accept()) {
                    Thread.sleep(5_000);
                } catch (IOException | InterruptedException ignoredException) {
                    // the test is over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            ProductClient client = clientWithTimeouts(silentServer.getLocalPort(), Duration.ofMillis(300));

            // Act
            long start = System.nanoTime();
            assertThrows(ProductServiceUnavailableException.class, () -> client.getProduct(BASKET_ID));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // Assert: it gave up after about the read timeout, not after the server's 5 seconds
            assertTrue(elapsedMillis < 3_000, "took " + elapsedMillis + " ms");
            acceptor.interrupt();
        }
    }
}
