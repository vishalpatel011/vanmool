package com.vanmool.cartservice.configs;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    // While the connection to Redis is down, the Lettuce client normally queues commands until it reconnects, so
    // every request would wait for the command timeout before failing. Rejecting them at once gives the client an
    // immediate 503 (GlobalExceptionHandler). Boot's other client options (timeouts) are kept
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
