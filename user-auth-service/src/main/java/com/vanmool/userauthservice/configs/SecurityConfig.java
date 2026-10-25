package com.vanmool.userauthservice.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // This service authenticates through its own endpoints and tokens, so Spring Security's browser defaults are off:
    // no login page, no HTTP Basic, no server-side HTTP session, and no CSRF protection, which guards cookie-based
    // sessions that this API does not use. Every endpoint is open for now; token and role checks come next.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    // Cost 12: about 0.3 s per hash on the development machine (measured: cost 10 = 75 ms, 11 = 150 ms, 12 = 294 ms).
    // Each step doubles the work for an attacker guessing passwords from a stolen hash, and for us at every login.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
