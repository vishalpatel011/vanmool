package com.vanmool.cartservice;

import com.sun.net.httpserver.HttpServer;
import com.vanmool.cartservice.clients.ProductClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

// What happens while a crashed instance is still in the registry: the load balancer picks it in its turn, the call
// cannot connect, and it is tried again on the next instance. Here product-service has two instances, one a port where
// nothing listens (the crashed one) and one that answers.
@SpringBootTest
class ProductClientRetryIT {
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final AtomicInteger LIVE_CALLS = new AtomicInteger();
    private static final HttpServer LIVE = liveInstance();
    private static final int DEAD_PORT = freePort();

    @Autowired
    private ProductClient productClient;

    private static HttpServer liveInstance() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/products/", exchange -> {
                LIVE_CALLS.incrementAndGet();
                byte[] body = ("{\"id\": \"" + BASKET_ID + "\", \"name\": \"Handcrafted Bamboo Basket\", \"price\": 499.99}")
                        .getBytes(StandardCharsets.UTF_8);
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

    // A port that was free a moment ago: connecting to it is refused, as to a process that has crashed
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void oneCrashedAndOneLiveInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[0].uri", () -> "http://127.0.0.1:" + DEAD_PORT);
        registry.add("spring.cloud.discovery.client.simple.instances.product-service[1].uri",
                () -> "http://127.0.0.1:" + LIVE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        LIVE.stop(0);
    }

    @Test
    void callsThatPickTheCrashedInstance_areRetriedOnTheOther_andAllSucceed() {
        // Act: six lookups; about half pick the crashed instance first
        for (int i = 0; i < 6; i++) {
            assertEquals("Handcrafted Bamboo Basket", productClient.getProduct(BASKET_ID).name());
        }

        // Assert: none failed, and the live instance answered all six
        assertEquals(6, LIVE_CALLS.get());
    }
}
