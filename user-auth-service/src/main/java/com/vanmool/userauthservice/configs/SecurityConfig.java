package com.vanmool.userauthservice.configs;

import com.vanmool.userauthservice.security.RestSecurityErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// Spring Security runs each request through one filter chain, the first whose matcher fits (a funnel of filters:
// authentication, then authorization). This service has three:
//   1. AuthorizationServerConfig: the OAuth2 / OpenID Connect endpoints (/oauth2/*, /.well-known/*, /userinfo)
//   2. apiSecurityFilterChain below: the REST API (/auth/**, /users/**), stateless, with bearer JWTs
//   3. browserSecurityFilterChain below: everything else, which is the sign-in form of the OAuth2 flow
@Configuration
public class SecurityConfig {

    // The REST API: no browser features (no login page, HTTP Basic or server session) and no CSRF protection,
    // which guards cookie-based sessions that this API does not use. Signup, login, logout and validate are open;
    // everything else needs a valid bearer JWT, and granting roles needs the ADMIN role.
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                                                      RestSecurityErrorHandler restSecurityErrorHandler) throws Exception {
        http
                .securityMatcher("/auth/**", "/users/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/users/*/roles/*").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolverOutsideAuth())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(restSecurityErrorHandler)
                        .accessDeniedHandler(restSecurityErrorHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(restSecurityErrorHandler)
                        .accessDeniedHandler(restSecurityErrorHandler));
        return http.build();
    }

    // Everything else is for a person in a browser: the sign-in form that the OAuth2 authorization endpoint sends
    // users to. Here a server-side session after sign-in, and CSRF protection on the form, are exactly right.
    @Bean
    @Order(3)
    public SecurityFilterChain browserSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    // The resource server checks the bearer token on every request that carries one, even on permitAll endpoints.
    // /auth/logout and /auth/validate read the token themselves (logging out an expired token is a no-op, and
    // /auth/validate gives its own answer), so under /auth/** the filter does not look for a token.
    private static BearerTokenResolver bearerTokenResolverOutsideAuth() {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        RequestMatcher authEndpoints = PathPatternRequestMatcher.withDefaults().matcher("/auth/**");
        return request -> authEndpoints.matches(request) ? null : defaultResolver.resolve(request);
    }

    // Turns the token's "roles" claim (["CUSTOMER", "SELLER"]) into Spring authorities (ROLE_CUSTOMER, ROLE_SELLER),
    // so hasRole("ADMIN") works for bearer tokens just as for signed-in users
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return jwtAuthenticationConverter;
    }
}
