package com.vanmool.apigateway.errors;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayErrorAttributesTest {

    @Test
    void aServiceWithoutInstances_or_oneThatCannotBeReached_is503() {
        // What the gateway's load balancer filter throws when a service has no instance up (as a 503, since the
        // gateway's use404 setting is off)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorAttributes.statusOf(
                NotFoundException.create(false, "Unable to find instance for product-service")));
        // A refused connection, however deep it is wrapped
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, GatewayErrorAttributes.statusOf(
                new RuntimeException(new IllegalStateException(new ConnectException("Connection refused")))));
    }

    @Test
    void aConnectionClosedByTheServiceWithoutAnAnswer_is502() {
        assertEquals(HttpStatus.BAD_GATEWAY, GatewayErrorAttributes.statusOf(PrematureCloseException.TEST_EXCEPTION));
        assertEquals(HttpStatus.BAD_GATEWAY, GatewayErrorAttributes.statusOf(
                new RuntimeException(PrematureCloseException.TEST_EXCEPTION)));
    }

    @Test
    void theGatewaysOwnAnswers_keepTheirStatus_andAnythingElseIs500() {
        assertEquals(HttpStatus.NOT_FOUND, GatewayErrorAttributes.statusOf(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, GatewayErrorAttributes.statusOf(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, GatewayErrorAttributes.statusOf(new IllegalStateException("bug")));
    }
}
