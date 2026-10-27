package com.vanmool.apigateway;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

    // An instance that reads each request and closes the connection without answering, as a service does when it
    // closes a kept-alive connection just as the gateway sends a request on it
    static final class ClosesWithoutAnswering {
        private final ServerSocket socket;
        final AtomicInteger calls = new AtomicInteger();

        ClosesWithoutAnswering() {
            try {
                socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Thread thread = new Thread(this::serve, "closes-without-answering");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket connection = socket.accept()) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1));
                    // The request line and the headers, up to the empty line; then the connection is closed
                    String line;
                    do {
                        line = reader.readLine();
                    } while (line != null && !line.isEmpty());
                    calls.incrementAndGet();
                } catch (IOException e) {
                    // stop() closed the socket, or the gateway gave up on the connection
                }
            }
        }

        String uri() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        void stop() {
            try {
                socket.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
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
