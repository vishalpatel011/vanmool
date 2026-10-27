package com.vanmool.apigateway.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.apigateway.revocation.RevokedTokens;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// Refuses a token that was ended before its expiry (logout, password change or reset). The services check tokens
// themselves, with user-auth-service's public keys, so on their own they would accept such a token until it expires.
// Only the token's id ("jti") is read here, from its payload, to look it up; checking the signature stays with the
// services, so a made-up token with an unknown id still fails there.
// Not for /auth/**: user-auth-service keeps its own list of ended tokens and answers those requests itself (logging
// out an ended token again is not an error there).
@Component
public class RevokedTokenFilter implements WebFilter, Ordered {
    private static final String BEARER = "Bearer ";

    private final RevokedTokens revokedTokens;
    private final ObjectMapper objectMapper;

    public RevokedTokenFilter(RevokedTokens revokedTokens, ObjectMapper objectMapper) {
        this.revokedTokens = revokedTokens;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)
                || exchange.getRequest().getPath().value().startsWith("/auth/")) {
            return chain.filter(exchange);
        }
        String tokenId = tokenIdOf(authorization.substring(BEARER.length()).trim());
        if (tokenId == null) {
            return chain.filter(exchange);
        }
        return revokedTokens.isRevoked(tokenId)
                .flatMap(revoked -> revoked ? refuse(exchange) : chain.filter(exchange));
    }

    // The "jti" claim of a JWT (header.payload.signature, Base64url), or null for anything that is not one
    private String tokenIdOf(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String jti = payload.path("jti").asText(null);
            return jti == null || jti.isBlank() ? null : jti;
        } catch (Exception e) {
            return null;
        }
    }

    // The same 401 the services give for a token they do not accept, in their error format
    private Mono<Void> refuse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 401);
        body.put("error", "INVALID_TOKEN");
        body.put("message", "The token has been revoked");
        body.put("path", exchange.getRequest().getPath().value());
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = "{\"error\":\"INVALID_TOKEN\"}".getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    // Right after RequestIdFilter, so a refused request still has its id; before routing
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
