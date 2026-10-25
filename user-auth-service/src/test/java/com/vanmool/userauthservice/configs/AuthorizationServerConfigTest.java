package com.vanmool.userauthservice.configs;

import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.models.User;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The rules of the authorization server that are Vanmool's own choices: which application may ask for tokens and how,
// and which claims go into which token. The full flow runs in OAuth2FlowIT.
class AuthorizationServerConfigTest {
    private static final UUID USER_ID = UUID.fromString("5b0e8f3c-2a41-4c47-9d0a-6f1c2e7b9a10");

    private final AuthorizationServerConfig config = new AuthorizationServerConfig();

    private static Authentication signedInAsha() {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("asha@example.com");
        user.setPasswordHash("$2a$12$ashasHash");
        user.getRoles().add(Role.SELLER);
        user.getRoles().add(Role.CUSTOMER);
        CustomUserDetails details = new CustomUserDetails(user);
        return UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
    }

    // Runs the customizer on a token being built and returns its claims
    private JwtClaimsSet customize(Authentication principal, OAuth2TokenType tokenType) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("asha@example.com");
        JwtEncodingContext context = JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claims)
                .principal(principal)
                .tokenType(tokenType)
                .build();
        config.tokenCustomizer().customize(context);
        return claims.build();
    }

    @Test
    void webClient_isAPublicClientThatMustUsePkceAndAsksNoConsent() {
        // Act
        RegisteredClient client = config.registeredClientRepository(List.of("http://127.0.0.1:3000/callback"))
                .findByClientId("vanmool-web");

        // Assert
        assertAll(
                () -> assertEquals(Set.of(ClientAuthenticationMethod.NONE), client.getClientAuthenticationMethods()),
                () -> assertEquals(Set.of(AuthorizationGrantType.AUTHORIZATION_CODE), client.getAuthorizationGrantTypes()),
                () -> assertTrue(client.getClientSettings().isRequireProofKey()),
                () -> assertFalse(client.getClientSettings().isRequireAuthorizationConsent()),
                () -> assertEquals(Set.of("http://127.0.0.1:3000/callback"), client.getRedirectUris()),
                () -> assertEquals(Set.of("openid", "profile", "email"), client.getScopes())
        );
    }

    @Test
    void accessToken_getsTheUserIdAsSubjectAndTheEmailAndRoles() {
        // Act
        JwtClaimsSet claims = customize(signedInAsha(), OAuth2TokenType.ACCESS_TOKEN);

        // Assert: the same identity claims as a token from POST /auth/login
        assertAll(
                () -> assertEquals(USER_ID.toString(), claims.getSubject()),
                () -> assertEquals("asha@example.com", claims.getClaimAsString("email")),
                () -> assertEquals(List.of("CUSTOMER", "SELLER"), claims.getClaim("roles"))
        );
    }

    @Test
    void idToken_getsTheUserIdAndEmailButNoRoles() {
        // Act: the ID token tells the app who signed in; what the user may do belongs in the access token
        JwtClaimsSet claims = customize(signedInAsha(), new OAuth2TokenType(OidcParameterNames.ID_TOKEN));

        // Assert
        assertEquals(USER_ID.toString(), claims.getSubject());
        assertEquals("asha@example.com", claims.getClaimAsString("email"));
        assertNull(claims.getClaim("roles"));
    }

    @Test
    void tokenNotForOneOfOurUsers_isLeftAsItIs() {
        // Arrange: for example a token for an application acting for itself, with no user signed in
        Authentication application = UsernamePasswordAuthenticationToken.authenticated("some-service", null, List.of());

        // Act
        JwtClaimsSet claims = customize(application, OAuth2TokenType.ACCESS_TOKEN);

        // Assert
        assertEquals("asha@example.com", claims.getSubject());
        assertNull(claims.getClaim("roles"));
    }
}
