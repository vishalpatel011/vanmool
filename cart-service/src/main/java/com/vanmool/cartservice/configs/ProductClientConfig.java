package com.vanmool.cartservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class ProductClientConfig {

    // Calls to other services go to a service name (http://product-service) instead of a host and port. Spring Cloud
    // LoadBalancer turns the name into one of the instances Eureka knows for it, taking them in turn (round robin).
    // A new builder for every client (prototype), with Spring Boot's JSON settings.
    // A name without an instance that is up makes the load balancer throw IllegalStateException ("No instances
    // available"), or IllegalArgumentException when it retries ("Service Instance cannot be null"). The clients would
    // not see that as a failed call (500 instead of 503), so this interceptor, which runs around the load balancer's,
    // turns it into an I/O error, as for a host that cannot be reached
    @Bean
    @LoadBalanced
    @Scope("prototype")
    public RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder())
                .requestInterceptor((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
                    } catch (IllegalStateException | IllegalArgumentException e) {
                        throw new IOException(e.getMessage(), e);
                    }
                });
    }

    // RestClient is the newer, fluent form of RestTemplate (Spring 6.1), built on the same HTTP machinery. Timeouts
    // are essential for a call to another service: without them, a product-service that hangs would hold this
    // service's request threads until they run out. With them, the cart answers 503 after at most a few seconds.
    @Bean
    public RestClient productRestClient(@LoadBalanced RestClient.Builder builder,
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
