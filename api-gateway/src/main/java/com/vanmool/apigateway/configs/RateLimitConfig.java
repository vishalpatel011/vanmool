package com.vanmool.apigateway.configs;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimitConfig {

    // Limits are counted per client address. Behind a load balancer (AWS), every request comes from the balancer's
    // address; the gateway must then take the client's address from X-Forwarded-For, which only the balancer may set
    // (server.forward-headers-strategy=native). No address: the request is refused (403), not let through unlimited
    @Bean
    public KeyResolver clientAddressKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(address -> address.getHostAddress());
    }

    // When an open connection to Redis breaks, the client queues commands until it reconnects, so every limited request
    // would wait for the timeout (500 ms) before being let through; rejecting them at once removes the wait. The same
    // setting as product-service's cache, where the wait was measured (about 1 s per request, two commands)
    @Bean
    public LettuceClientConfigurationBuilderCustomizer rejectCommandsWhileRedisIsDisconnected() {
        return builder -> {
            ClientOptions options = builder.build().getClientOptions().orElseGet(ClientOptions::create);
            builder.clientOptions(options.mutate()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
        };
    }
}
