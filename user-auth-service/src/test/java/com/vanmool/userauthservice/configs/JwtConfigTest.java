package com.vanmool.userauthservice.configs;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// How the signing key reaches the service: from JWT_PRIVATE_KEY in the forms an environment variable can hold
class JwtConfigTest {
    private final JwtConfig jwtConfig = new JwtConfig();
    private final KeyPair keyPair = generate();

    private static KeyPair generate() {
        try {
            return JwtConfig.generateRsaKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String base64PrivateKey() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private String pem() {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void pemPrivateKey_isLoadedWithItsPublicKey() throws Exception {
        // Act
        RSAKey key = jwtConfig.jwtSigningKey(pem());

        // Assert: the public key is rebuilt from the private key's data
        assertEquals(keyPair.getPublic(), key.toRSAPublicKey());
        assertEquals(keyPair.getPrivate(), key.toRSAPrivateKey());
    }

    @Test
    void base64OnOneLine_andPemWithEscapedLineBreaks_areLoadedToo() throws Exception {
        // Arrange: how a PEM often ends up in an environment variable or a cloud secret
        String escapedPem = pem().replace("\n", "\\n");

        // Act & Assert
        assertEquals(keyPair.getPublic(), jwtConfig.jwtSigningKey(base64PrivateKey()).toRSAPublicKey());
        assertEquals(keyPair.getPublic(), jwtConfig.jwtSigningKey(escapedPem).toRSAPublicKey());
    }

    @Test
    void sameKey_alwaysGetsTheSameKeyId() throws Exception {
        // Act: the kid is the key's thumbprint, so every instance and every restart publishes the same kid
        String firstKid = jwtConfig.jwtSigningKey(pem()).getKeyID();
        String secondKid = jwtConfig.jwtSigningKey(base64PrivateKey()).getKeyID();

        // Assert
        assertNotNull(firstKid);
        assertEquals(firstKid, secondKid);
    }

    @Test
    void withoutAConfiguredKey_aNewKeyIsGeneratedEachTime() throws Exception {
        // Act
        RSAKey first = jwtConfig.jwtSigningKey("");
        RSAKey second = jwtConfig.jwtSigningKey("  ");

        // Assert: this is why JWT_PRIVATE_KEY must be set outside local development
        assertNotEquals(first.toRSAPublicKey(), second.toRSAPublicKey());
        assertNotEquals(first.getKeyID(), second.getKeyID());
    }

    @Test
    void textThatIsNotAKey_failsAtStartup() {
        // Act & Assert
        assertThrows(Exception.class, () -> jwtConfig.jwtSigningKey("not-a-key"));
    }
}
