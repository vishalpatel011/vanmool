package com.vanmool.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// The one address clients use (Day 222). Each request is routed by its path to the service that owns it, and to one of
// that service's instances (lb://, through Eureka). The routes are in application.properties. No business logic here:
// the gateway only routes, labels each request with an id, logs it, and answers when no service can.
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
