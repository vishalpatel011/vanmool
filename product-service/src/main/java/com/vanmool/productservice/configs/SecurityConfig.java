package com.vanmool.productservice.configs;

import com.vanmool.productservice.security.RestSecurityErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

// Who may do what in the catalog:
//   reading products, categories and stock     anyone, without a token: shoppers browse before they sign in
//   creating, changing, deleting products      a SELLER (a producer) or an ADMIN
//   adjusting stock                            a SELLER or an ADMIN
//   reserving and releasing stock              any signed-in user's token: order-service makes these calls for the
//                                              customer who checks out, with that customer's token. The API gateway
//                                              does not expose them, so only the services can reach them
// Anything else needs a token, so a new endpoint is never open by mistake. Tokens are checked here with
// user-auth-service's public keys (JwtDecoderConfig); this service never sees a password.
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RestSecurityErrorHandler restSecurityErrorHandler,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/products", "/products/**", "/categories", "/categories/**").permitAll()
                        .requestMatchers("/stock-reservations", "/stock-reservations/**").authenticated()
                        .requestMatchers("/products", "/products/**").hasAnyRole("SELLER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(restSecurityErrorHandler)
                        .accessDeniedHandler(restSecurityErrorHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(restSecurityErrorHandler)
                        .accessDeniedHandler(restSecurityErrorHandler));
        return http.build();
    }

    // The token's "roles" claim (["CUSTOMER", "SELLER"]) becomes ROLE_CUSTOMER, ROLE_SELLER, so hasAnyRole works
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
