package com.vanmool.apigateway.filters;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    // Runs the filter; returns the id the service would get, after checking the client gets the same one back
    private String requestIdFor(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> passedOn = new AtomicReference<>();
        filter.filter(exchange, next -> {
            passedOn.set(next);
            return next.getResponse().setComplete();
        }).block();
        String toService = passedOn.get().getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        assertEquals(toService, exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER));
        return toService;
    }

    @Test
    void aRequestWithoutAnId_getsANewOne_forTheServiceAndTheClient() {
        String id = requestIdFor(MockServerHttpRequest.get("/products"));

        assertDoesNotThrow(() -> UUID.fromString(id));
    }

    @Test
    void anIdTheClientSent_isKept() {
        assertEquals("checkout-7f3a", requestIdFor(MockServerHttpRequest.get("/orders").header(RequestIdFilter.HEADER, "checkout-7f3a")));
    }

    @Test
    void anIdThatIsNotAPlainId_isReplaced() {
        // Something that would start a new, forged line in the log, and one that is far too long
        String forged = requestIdFor(MockServerHttpRequest.get("/orders").header(RequestIdFilter.HEADER, "abc\n2026 INFO forged"));
        String tooLong = requestIdFor(MockServerHttpRequest.get("/orders").header(RequestIdFilter.HEADER, "a".repeat(65)));

        assertDoesNotThrow(() -> UUID.fromString(forged));
        assertNotEquals("a".repeat(65), tooLong);
    }

    @Test
    void theRequestIsPassedOnUnchangedOtherwise() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/cart/items?x=1").header("Authorization", "Bearer eyJ.a.b"));
        AtomicReference<ServerWebExchange> passedOn = new AtomicReference<>();

        filter.filter(exchange, next -> {
            passedOn.set(next);
            return Mono.empty();
        }).block();

        assertEquals("/cart/items", passedOn.get().getRequest().getPath().value());
        assertEquals("x=1", passedOn.get().getRequest().getURI().getQuery());
        assertEquals("Bearer eyJ.a.b", passedOn.get().getRequest().getHeaders().getFirst("Authorization"));
    }
}
