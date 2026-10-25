package com.vanmool.userauthservice.configs;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vanmool.userauthservice.security.RevokedTokenValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

// One RSA key pair signs every token this service issues: the JWTs from POST /auth/login and the OAuth2 tokens from
// /oauth2/token. Tokens are signed with the private key (RS256) and checked with the public key, which /oauth2/jwks
// publishes. Other services can therefore check tokens with the public key alone and cannot create any, which a
// shared HS256 secret would allow.
@Slf4j
@Configuration
public class JwtConfig {

    // The private key comes from JWT_PRIVATE_KEY: a PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----"), or the same Base64
    // on one line. Every instance must use the same key, or one instance rejects another's tokens, and the key must
    // never be in the repository. Without it, a key is generated at startup: fine on a developer's machine only.
    @Bean
    public RSAKey jwtSigningKey(@Value("${auth.jwt.private-key:}") String privateKey) throws GeneralSecurityException, JOSEException {
        KeyPair keyPair;
        if(privateKey.isBlank()) {
            log.warn("JWT_PRIVATE_KEY is not set: signing tokens with a key generated at startup. Tokens stop working when "
                    + "the service restarts, and instances do not accept each other's tokens. Set JWT_PRIVATE_KEY outside local development.");
            keyPair = generateRsaKeyPair();
        } else {
            keyPair = loadRsaKeyPair(privateKey);
        }
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        // The key id (kid) is the key's RFC 7638 thumbprint, so the same key always gets the same id
        return new RSAKey.Builder(rsaKey).keyID(rsaKey.computeThumbprint().toString()).build();
    }

    // The authorization server signs its tokens with the key from here and publishes the public part at /oauth2/jwks
    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey jwtSigningKey) {
        return new ImmutableJWKSet<>(new JWKSet(jwtSigningKey));
    }

    // Checks the bearer tokens sent to protected endpoints (/users/**, /userinfo): RS256 signature, expiry, issuer, and
    // that a token ended by logout is not used again
    @Bean
    public JwtDecoder jwtDecoder(RSAKey jwtSigningKey, @Value("${auth.issuer}") String issuer,
                                 RevokedTokenValidator revokedTokenValidator) throws JOSEException {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey()).build();
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), revokedTokenValidator));
        return jwtDecoder;
    }

    static KeyPair loadRsaKeyPair(String privateKey) throws GeneralSecurityException {
        String base64 = privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "") // a PEM pasted into an environment variable with escaped line breaks
                .replaceAll("\\s", "");
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        // The public key is part of the private key's data (modulus and public exponent)
        RSAPublicKey rsaPublicKey = (RSAPublicKey) keyFactory.generatePublic(
                new RSAPublicKeySpec(rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent()));
        return new KeyPair(rsaPublicKey, rsaPrivateKey);
    }

    static KeyPair generateRsaKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }
}
