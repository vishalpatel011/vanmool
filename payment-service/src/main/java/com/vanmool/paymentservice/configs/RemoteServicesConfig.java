package com.vanmool.paymentservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RemoteServicesConfig {

    // order-service, with timeouts: a hanging order-service makes a payment start fail with 503 within seconds
    @Bean
    public RestClient orderRestClient(RestClient.Builder builder, @Value("${order-service.url}") String baseUrl,
                                      @Value("${remote-services.connect-timeout}") Duration connectTimeout,
                                      @Value("${remote-services.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
