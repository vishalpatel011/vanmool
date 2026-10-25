package com.vanmool.cartservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class ProductClientConfig {

    // RestClient is the newer, fluent form of RestTemplate (Spring 6.1), built on the same HTTP machinery. Timeouts
    // are essential for a call to another service: without them, a product-service that hangs would hold this
    // service's request threads until they run out. With them, the cart answers 503 after at most a few seconds.
    @Bean
    public RestClient productRestClient(RestClient.Builder builder,
                                        @Value("${product-service.url}") String baseUrl,
                                        @Value("${product-service.connect-timeout}") Duration connectTimeout,
                                        @Value("${product-service.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
