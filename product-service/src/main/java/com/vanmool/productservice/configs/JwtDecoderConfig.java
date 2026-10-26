package com.vanmool.productservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

// Checking a token needs user-auth-service's public keys (its JWKS). They are fetched from user-auth-service by its
// service name, http://user-auth-service/oauth2/jwks, through Eureka and the load balancer, like any call between the
// services (Day 223: product-service calls user-auth-service through Eureka). The keys are cached, so this is one call
// every few minutes, not one per request; the tokens themselves are checked here, without asking user-auth-service.
@Configuration
public class JwtDecoderConfig {

    @Bean
    @LoadBalanced
    public RestTemplate userAuthRestTemplate(RestTemplateBuilder builder) {
        return builder.connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(3)).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@LoadBalanced RestTemplate userAuthRestTemplate,
                                 @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                                 @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(userAuthRestTemplate).build();
        // Signature (with the fetched keys), expiry, and that user-auth-service issued it
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
