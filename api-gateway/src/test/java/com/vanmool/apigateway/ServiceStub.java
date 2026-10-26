package com.vanmool.apigateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

// Plays one instance of a service for the gateway tests: answers every request with JSON naming the service, the path
// and query it received and the X-Request-Id header it got, after an optional delay.
final class ServiceStub {
    private final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();

    ServiceStub(String serviceName, Duration delay) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            String query = exchange.getRequestURI().getRawQuery();
            byte[] body = ("{\"service\": \"" + serviceName + "\", \"method\": \"" + exchange.getRequestMethod()
                    + "\", \"path\": \"" + exchange.getRequestURI().getRawPath() + "\", \"query\": "
                    + (query == null ? "null" : "\"" + query + "\"") + ", \"requestId\": "
                    + (requestId == null ? "null" : "\"" + requestId + "\"") + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    ServiceStub(String serviceName) {
        this(serviceName, Duration.ZERO);
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    // The address of a port that was free a moment ago: connecting to it is refused, as to a crashed instance
    static String deadInstance() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
