package com.vanmool.paymentservice.clients;

import com.vanmool.paymentservice.exceptions.OrderNotFoundException;
import com.vanmool.paymentservice.exceptions.OrderServiceUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

// Asks order-service for the order being paid, with the customer's own token: order-service answers only for the
// customer's own orders, and its total is the amount charged. A client never sends an amount.
@Component
public class OrderClient {
    private final RestClient orderRestClient;

    public OrderClient(@Qualifier("orderRestClient") RestClient orderRestClient) {
        this.orderRestClient = orderRestClient;
    }

    // The part of order-service's GET /orders/{id} that a payment needs
    public record Order(UUID id, String status, BigDecimal totalAmount) {
    }

    public Order getOrder(UUID orderId, String bearerToken) {
        try {
            return orderRestClient.get()
                    .uri("/orders/{id}", orderId)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .body(Order.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new OrderNotFoundException(orderId);
        } catch (RestClientException e) {
            throw new OrderServiceUnavailableException(e);
        }
    }
}
