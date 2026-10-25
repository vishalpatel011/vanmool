package com.vanmool.orderservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// One RestClient per service order-service calls. Both have connect and read timeouts: a service that hangs makes
// the order fail with 503 after a few seconds instead of holding this service's request threads.
@Configuration
public class RemoteServicesConfig {

    @Bean
    public RestClient cartRestClient(RestClient.Builder builder, @Value("${cart-service.url}") String baseUrl,
                                     @Value("${remote-services.connect-timeout}") Duration connectTimeout,
                                     @Value("${remote-services.read-timeout}") Duration readTimeout) {
        return restClient(builder, baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    public RestClient productRestClient(RestClient.Builder builder, @Value("${product-service.url}") String baseUrl,
                                        @Value("${remote-services.connect-timeout}") Duration connectTimeout,
                                        @Value("${remote-services.read-timeout}") Duration readTimeout) {
        return restClient(builder, baseUrl, connectTimeout, readTimeout);
    }

    // The builder is a prototype bean, so each client gets its own copy
    static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
