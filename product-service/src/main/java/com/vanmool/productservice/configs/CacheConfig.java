package com.vanmool.productservice.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.productservice.dtos.ProductResponseDto;
import io.lettuce.core.ClientOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

// Product details are cached in Redis (cache-aside): GET /products/{id} first looks for the key product:{id}; on a miss
// it reads MySQL and stores the result. Changing or deleting a product removes its key (ProductDbService).
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {
    public static final String PRODUCT_CACHE = "product";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper,
                                          @Value("${product.cache.time-to-live}") Duration timeToLive) {
        // The value is the response DTO as JSON, not the entity: it is exactly what the API returns, readable in
        // redis-cli, and free of Hibernate state. The TTL is a safety net for changes made outside this service
        // (for example directly in MySQL); changes through the API remove the key at once.
        RedisCacheConfiguration productCache = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(timeToLive)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> cacheName + ":")
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, ProductResponseDto.class)));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(PRODUCT_CACHE, productCache)
                // Only the cache configured above exists; a misspelt cache name fails instead of creating a new cache
                .disableCreateOnMissingCache()
                // Inside a transaction, puts and evictions wait until it commits: a product changed in a transaction
                // that then rolls back keeps its cache entry, and the new data is never cached before it is in MySQL
                .transactionAware()
                .build();
    }

    // While the connection to Redis is down, the Lettuce client normally queues commands until it reconnects, so each
    // cache call would wait the full timeout (measured: about 1 s per request, a read plus a write). Rejecting them
    // at once lets the request go straight to MySQL. Boot's other client options (timeouts) are kept
    @Bean
    public LettuceClientConfigurationBuilderCustomizer rejectCommandsWhileRedisIsDisconnected() {
        return builder -> {
            ClientOptions options = builder.build().getClientOptions().orElseGet(ClientOptions::create);
            builder.clientOptions(options.mutate()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
        };
    }

    // Redis is an optimisation, not the source of truth: if it is down or slow (spring.data.redis.timeout), a failed
    // cache read or write is logged as a warning and the request is served from MySQL instead of failing
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
