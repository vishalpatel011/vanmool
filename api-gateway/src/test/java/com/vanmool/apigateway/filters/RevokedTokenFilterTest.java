package com.vanmool.apigateway.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.apigateway.revocation.RevokedTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RevokedTokenFilterTest {
    private static final String ENDED = "0f6e2b8a-77d4-4e8a-b1f3-3c2d9a5e4b71";
    private static final String ACTIVE = "9a1d6c2e-0b37-4f58-8e21-7c4b3a5d6e90";

    private final RevokedTokens revokedTokens = mock(RevokedTokens.class);
    private final RevokedTokenFilter filter = new RevokedTokenFilter(revokedTokens, new ObjectMapper());

    // A JWT-shaped token with the given id; the gateway reads only the id, the services check the signature
    private static String tokenWithId(String jti) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(("{\"sub\":\"asha\",\"jti\":\"" + jti + "\"}").getBytes(StandardCharsets.UTF_8))
                + ".c2lnbmF0dXJl";
    }

    // Runs the filter; true if the request went on to the service
    private boolean passesOn(MockServerWebExchange exchange) {
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, next -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        return passed.get();
    }

    @Test
    void anEndedToken_isRefusedWith401_inTheServicesErrorFormat() {
        when(revokedTokens.isRevoked(ENDED)).thenReturn(Mono.just(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithId(ENDED)));

        assertFalse(passesOn(exchange));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("Bearer", exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
        String body = exchange.getResponse().getBodyAsString().block();
        assertTrue(body.contains("\"error\":\"INVALID_TOKEN\"") && body.contains("\"message\":\"The token has been revoked\"")
                && body.contains("\"path\":\"/cart\""), body);
    }

    @Test
    void aTokenThatWasNotEnded_isPassedOn() {
        when(revokedTokens.isRevoked(ACTIVE)).thenReturn(Mono.just(false));

        assertTrue(passesOn(MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithId(ACTIVE)))));
    }

    @Test
    void requestsWithoutABearerToken_orWithSomethingThatIsNotAJwt_arePassedOn_forTheServiceToJudge() {
        assertTrue(passesOn(MockServerWebExchange.from(MockServerHttpRequest.get("/products"))));
        assertTrue(passesOn(MockServerWebExchange.from(MockServerHttpRequest.get("/cart").header(HttpHeaders.AUTHORIZATION, "Basic YTpi"))));
        assertTrue(passesOn(MockServerWebExchange.from(MockServerHttpRequest.get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))));
        assertTrue(passesOn(MockServerWebExchange.from(MockServerHttpRequest.get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer a.%%%.c"))));
        verifyNoInteractions(revokedTokens);
    }

    @Test
    void theAuthEndpoints_areLeftToUserAuthService() {
        // Logging out an ended token again is not an error at user-auth-service
        assertTrue(passesOn(MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithId(ENDED)))));
        verifyNoInteractions(revokedTokens);
    }

    @Test
    void whenTheCheckItselfFails_theRequestIsPassedOn() {
        // RevokedTokens answers false when Redis cannot be reached
        when(revokedTokens.isRevoked(anyString())).thenReturn(Mono.just(false));

        assertTrue(passesOn(MockServerWebExchange.from(
                MockServerHttpRequest.get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithId(ENDED)))));
    }
}
