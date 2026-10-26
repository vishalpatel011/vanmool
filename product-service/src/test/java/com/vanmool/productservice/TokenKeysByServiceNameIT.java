package com.vanmool.productservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Day 223: product-service gets user-auth-service's public keys by its service name. A small HTTP server plays
// user-auth-service and is the one instance of that name (Eureka is off in tests, so the instance is given as a
// property); the keys are asked for at http://user-auth-service/oauth2/jwks, as configured. Tokens are signed here.
// Needs the MySQL, Redis and Kafka of the other integration tests.
@SpringBootTest
@AutoConfigureMockMvc
class TokenKeysByServiceNameIT {
    private static final String ISSUER = "http://localhost:8081";
    private static final AtomicInteger KEY_REQUESTS = new AtomicInteger();
    private static final RSAKey SIGNING_KEY;
    private static final RSAKey OTHER_KEY;
    private static final HttpServer USER_AUTH;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID("vanmool-test-key").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("vanmool-test-key").generate();
            byte[] jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            USER_AUTH = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            USER_AUTH.createContext("/oauth2/jwks", exchange -> {
                KEY_REQUESTS.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            USER_AUTH.start();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void userAuthServiceInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-auth-service[0].uri",
                () -> "http://127.0.0.1:" + USER_AUTH.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        USER_AUTH.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    private static String token(RSAKey key) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER")).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1)))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void aTokenSignedWithUserAuthServicesKey_isAccepted_withTheKeysFetchedByServiceName() throws Exception {
        // Releasing the stock of an order that has none is allowed and changes nothing (releases are idempotent)
        mockMvc.perform(delete("/stock-reservations/{orderId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY)))
                .andExpect(status().isNoContent());

        assertTrue(KEY_REQUESTS.get() >= 1, "the keys were never fetched from user-auth-service");
    }

    @Test
    void aTokenSignedWithAnotherKey_isRefused() throws Exception {
        mockMvc.perform(delete("/stock-reservations/{orderId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(OTHER_KEY)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }
}
