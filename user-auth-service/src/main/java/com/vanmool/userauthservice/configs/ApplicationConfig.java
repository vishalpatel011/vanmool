package com.vanmool.userauthservice.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ApplicationConfig {
    // Token expiry is computed from this clock; tests replace it with a fixed one, so "expired" is exact
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
