package com.vanmool.servicediscovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

// The service registry (Day 223). Every service registers its address here when it starts and renews it every 30 s;
// a service that needs another asks for that service's instances by name (product-service, user-auth-service) and
// picks one itself (client-side load balancing). Nothing is routed through here: the registry only answers who is
// where. Its dashboard is at http://localhost:8761
@SpringBootApplication
@EnableEurekaServer
public class ServiceDiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceDiscoveryApplication.class, args);
    }
}
