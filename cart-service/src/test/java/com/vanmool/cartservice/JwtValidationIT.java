package com.vanmool.cartservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The real token checking of application.properties, with real signed JWTs. A small HTTP server on this machine
// plays user-auth-service's JWKS endpoint and publishes one public key; tokens are signed here with its private key,
// or with another key, another issuer or an expiry in the past. Needs the Redis started by docker compose.
@SpringBootTest
@AutoConfigureMockMvc
class JwtValidationIT {
    private static final String ISSUER = "http://localhost:8081";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final RSAKey SIGNING_KEY;
    private static final RSAKey UNPUBLISHED_KEY;
    private static final HttpServer JWKS_SERVER;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID("vanmool-test-key").generate();
            // Same key id, different key: only the signature check can tell them apart
            UNPUBLISHED_KEY = new RSAKeyGenerator(2048).keyID("vanmool-test-key").generate();
            byte[] jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS_SERVER.createContext("/oauth2/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            JWKS_SERVER.start();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void tokenSettings(DynamicPropertyRegistry registry) {
        // The keys are asked for at http://user-auth-service/oauth2/jwks, as configured: the local server is the one
        // instance of that service name (Eureka is off in tests)
        registry.add("spring.cloud.discovery.client.simple.instances.user-auth-service[0].uri",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    private static String token(RSAKey key, String issuer, Instant expiresAt) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(USER_ID.toString())
                .claim("roles", List.of("CUSTOMER"))
                .issueTime(Date.from(expiresAt.minus(Duration.ofHours(10))))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static Instant inOneHour() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    @Test
    void tokenSignedWithThePublishedKey_isAccepted() throws Exception {
        mockMvc.perform(get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SIGNING_KEY, ISSUER, inOneHour())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    @Test
    void tokenSignedWithAnotherKey_isRefused() throws Exception {
        mockMvc.perform(get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UNPUBLISHED_KEY, ISSUER, inOneHour())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void tokenFromAnotherIssuer_isRefused() throws Exception {
        mockMvc.perform(get("/cart").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(SIGNING_KEY, "https://attacker.example", inOneHour())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_isRefused() throws Exception {
        // An hour ago: well past the minute of clock difference that is tolerated
        mockMvc.perform(get("/cart").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token(SIGNING_KEY, ISSUER, Instant.now().minus(Duration.ofHours(1)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedToken_isRefused() throws Exception {
        // "alg": "none": the claims are fine, but there is no signature at all
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer(ISSUER).subject(USER_ID.toString())
                .expirationTime(Date.from(inOneHour())).build()).serialize();
        mockMvc.perform(get("/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + unsigned))
                .andExpect(status().isUnauthorized());
    }
}
