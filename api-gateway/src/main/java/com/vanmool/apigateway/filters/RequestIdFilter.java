package com.vanmool.apigateway.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

// Gives every request an id and logs it once it is answered. The id travels to the service in the X-Request-Id header
// and back to the client in the same header, so a problem a customer reports can be found in the logs. A client may
// send its own id; one that is not a plain id (letters, digits, hyphens, at most 64) is replaced, so nothing odd can be
// written into the logs. A WebFilter, not a gateway filter, so it also covers requests that match no route.
// The log line has the path without its query: a query can hold a secret (the token of a password reset link).
@Component
public class RequestIdFilter implements WebFilter, Ordered {
    public static final String HEADER = "X-Request-Id";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String sent = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = sent != null && VALID_ID.matcher(sent).matches() ? sent : UUID.randomUUID().toString();
        ServerWebExchange withId = exchange.mutate().request(request -> request.headers(headers -> headers.set(HEADER, requestId))).build();
        withId.getResponse().beforeCommit(() -> {
            withId.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        long start = System.nanoTime();
        return chain.filter(withId).doFinally(signal -> {
            Route route = withId.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            HttpStatusCode status = withId.getResponse().getStatusCode();
            log.info("{} {} -> {} {} in {} ms [{}]", withId.getRequest().getMethod(), withId.getRequest().getPath(),
                    route == null ? "no route" : route.getId(), status == null ? "-" : status.value(),
                    (System.nanoTime() - start) / 1_000_000, requestId);
        });
    }

    // First, so that every later filter, the route and the error handler see the id
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
