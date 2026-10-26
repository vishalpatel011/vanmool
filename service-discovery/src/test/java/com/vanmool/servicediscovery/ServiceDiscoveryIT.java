package com.vanmool.servicediscovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The registry on a free port, used through Eureka's REST API as a service's Eureka client uses it: register an
// instance, find it by its service name, remove it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceDiscoveryIT {
    private static final String INSTANCE = """
            { "instance": {
                "instanceId": "test-host:product-service:18080", "hostName": "127.0.0.1", "app": "PRODUCT-SERVICE",
                "ipAddr": "127.0.0.1", "status": "UP", "port": { "$": 18080, "@enabled": "true" },
                "dataCenterInfo": { "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo", "name": "MyOwn" } } }""";

    @Autowired
    private TestRestTemplate restTemplate;

    private static HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    @Test
    void anInstanceThatRegisters_isFoundByItsServiceName_untilItLeaves() {
        // Register, as a service does when it starts
        ResponseEntity<String> registered = restTemplate.exchange("/eureka/apps/PRODUCT-SERVICE", HttpMethod.POST,
                new HttpEntity<>(INSTANCE, json()), String.class);
        assertEquals(HttpStatus.NO_CONTENT, registered.getStatusCode());

        // Look it up by name, as a caller's load balancer does
        ResponseEntity<String> found = restTemplate.exchange("/eureka/apps/PRODUCT-SERVICE", HttpMethod.GET,
                new HttpEntity<>(json()), String.class);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertTrue(found.getBody().contains("\"ipAddr\":\"127.0.0.1\"") && found.getBody().contains("18080"), found.getBody());

        // Leave, as a service does when it shuts down: the instance is gone from the registry at once. (The list for
        // the whole service, read above, comes from a response cache refreshed every 30 s, so callers see the
        // change up to 30 s later; their own copies and load balancer caches add to that.)
        restTemplate.delete("/eureka/apps/PRODUCT-SERVICE/test-host:product-service:18080");
        ResponseEntity<String> gone = restTemplate.exchange("/eureka/apps/PRODUCT-SERVICE/test-host:product-service:18080",
                HttpMethod.GET, new HttpEntity<>(json()), String.class);
        assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode());
    }

    @Test
    void healthAndDashboard_areServed() {
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/actuator/health", String.class).getStatusCode());
        ResponseEntity<String> dashboard = restTemplate.getForEntity("/", String.class);
        assertEquals(HttpStatus.OK, dashboard.getStatusCode());
        assertTrue(dashboard.getBody().contains("Instances currently registered with Eureka"));
    }
}
