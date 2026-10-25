package com.vanmool.userauthservice.configs;

import com.vanmool.userauthservice.models.Role;
import com.vanmool.userauthservice.security.models.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.time.Duration;
import java.util.List;

// The OAuth2 Authorization Server with OpenID Connect (OIDC). OAuth2 lets an application get a token to act for a
// user without ever seeing the user's password: the user signs in here, on the service that owns the passwords.
// OIDC adds identity on top: an ID token saying who signed in, and /userinfo.
// Endpoints: /oauth2/authorize, /oauth2/token, /oauth2/jwks, /oauth2/revoke, /oauth2/introspect,
// /.well-known/openid-configuration, /userinfo, /connect/logout.
@Configuration
public class AuthorizationServerConfig {

    // Filter chain 1 of 3 (see SecurityConfig): only the authorization server's own endpoints. A person who is not
    // signed in is sent from /oauth2/authorize to the sign-in form at /login; /userinfo accepts a bearer token.
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    // The applications allowed to ask for tokens. "vanmool-web" is the future Vanmool web or mobile front end: a
    // public client (it cannot keep a secret), so it uses the authorization code flow with PKCE, which proves that
    // the app redeeming a code is the one that asked for it. It is Vanmool's own app, so no consent screen is shown.
    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Value("${auth.oauth2.web-client.redirect-uris}") List<String> redirectUris) {
        RegisteredClient webClient = RegisteredClient.withId("vanmool-web")
                .clientId("vanmool-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(uris -> uris.addAll(redirectUris))
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(30)).build())
                .build();
        return new InMemoryRegisteredClientRepository(webClient);
    }

    // The issuer ("iss") written into every token and published in /.well-known/openid-configuration; behind the
    // API gateway or on AWS it is the public URL, set through AUTH_ISSUER
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(@Value("${auth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    // OAuth2 tokens get the same identity claims as the JWTs from POST /auth/login: "sub" is the user's id (not the
    // email, which can change) and the access token carries the email and roles, so a service can authorize a
    // request from either kind of token in the same way
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if(!(context.getPrincipal().getPrincipal() instanceof CustomUserDetails user)) {
                return;
            }
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
            boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
            if(accessToken || idToken) {
                context.getClaims().subject(user.getUserId().toString()).claim("email", user.getUsername());
            }
            if(accessToken) {
                context.getClaims().claim("roles", user.getRoles().stream().sorted().map(Role::name).toList());
            }
        };
    }
}
