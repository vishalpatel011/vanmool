package com.vanmool.userauthservice.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
public class ApplicationConfig {
    // Token expiry is computed from this clock; tests replace it with a fixed one, so "expired" is exact
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // Cost 12: about 0.3 s per hash on the development machine (measured: cost 10 = 75 ms, 11 = 150 ms, 12 = 294 ms).
    // Each step doubles the work for an attacker guessing passwords from a stolen hash, and for us at every login.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // The authentication manager Spring Security builds from CustomUserDetailsService and the PasswordEncoder above
    // (a DaoAuthenticationProvider). POST /auth/login uses it, so the API and the OAuth2 sign-in form check passwords
    // in exactly the same way.
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
