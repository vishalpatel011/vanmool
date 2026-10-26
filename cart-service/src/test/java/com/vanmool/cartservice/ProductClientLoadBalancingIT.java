package com.vanmool.cartservice;

import com.sun.net.httpserver.HttpServer;
import com.vanmool.cartservice.clients.ProductClient;
import com.vanmool.cartservice.clients.ProductDetails;
import com.vanmool.cartservice.exceptions.ProductServiceUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Client-side load balancing through the real ProductClient that Spring builds: it calls http://product-service, a
// service name, and Spring Cloud LoadBalancer picks an instance for every call. Two small HTTP servers on this machine
// play two instances of product-service. Eureka is off in tests, so the instances of the name are given as properties
// (Spring Cloud's simple discovery client), as Eureka would give them.
@SpringBootTest
class ProductClientLoadBalancingIT {
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final AtomicInteger FIRST_CALLS = new AtomicInteger();
    private static final AtomicInteger SECOND_CALLS = new AtomicInteger();
    private static final HttpServer FIRST = instance(FIRST_CALLS);
    private static final HttpServer SECOND = instance(SECOND_CALLS);

    @Autowired
    private ProductClient productClient;

    // The same load-balanced builder ProductClientConfig gets (a new one on every injection)
    @Autowired
    @LoadBalanced
    private RestClient.Builder loadBalancedBuilder;

    // One instance of product-service: answers every product request with the basket
    private static HttpServer instance(AtomicInteger calls) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/products/", exchange -> {
                calls.incrementAndGet();
                byte[] body = ("{\"id\": \"" + BASKET_ID + "\", \"name\": \"Handcrafted Bamboo Basket\", \"price\": 499.99, "
                        + "\"imageUrl\": \"https://example.com/images/bamboo-basket.jpg\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void twoInstancesOfProductService(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri",
                () -> "http://127.0.0.1:" + FIRST.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[1].uri",
                () -> "http://127.0.0.1:" + SECOND.getAddress().getPort());
    }

    @AfterAll
    static void stopInstances() {
        FIRST.stop(0);
        SECOND.stop(0);
    }

    @Test
    void callsToTheServiceName_areSharedBetweenItsInstancesInTurn() {
        // Act: six product lookups by the cart
        for (int i = 0; i < 6; i++) {
            ProductDetails basket = productClient.getProduct(BASKET_ID);
            assertEquals("Handcrafted Bamboo Basket", basket.name());
        }

        // Assert: round robin, three each
        assertEquals(3, FIRST_CALLS.get());
        assertEquals(3, SECOND_CALLS.get());
    }

    @Test
    void anUnknownServiceName_isAFailedCall_notAHang() {
        // A name without instances (not registered, or every instance down) fails at once, as an unreachable service
        // does, and the cart answers 503 for it
        ProductClient unknown = new ProductClient(loadBalancedBuilder.baseUrl("http://no-such-service").build());
        assertThrows(ProductServiceUnavailableException.class, () -> unknown.getProduct(BASKET_ID));
    }
}
