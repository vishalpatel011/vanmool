package com.vanmool.orderservice.clients;

import com.vanmool.orderservice.exceptions.CartServiceUnavailableException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

// Calls cart-service with the customer's own token (passed on unchanged), so cart-service returns this customer's
// cart and nobody else's. order-service needs no credentials of its own for this.
@Component
public class CartClient {
    private final RestClient cartRestClient;

    public CartClient(@Qualifier("cartRestClient") RestClient cartRestClient) {
        this.cartRestClient = cartRestClient;
    }

    // One line of cart-service's GET /cart/items: ids and quantities only. The priced GET /cart is not used: it
    // would ask product-service for every line, and the order takes its prices from the stock reservation anyway
    public record CartLine(UUID productId, int quantity) {
    }

    public List<CartLine> getCartLines(String bearerToken) {
        try {
            List<CartLine> lines = cartRestClient.get()
                    .uri("/cart/items")
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return lines == null ? List.of() : lines;
        } catch (RestClientException e) {
            throw new CartServiceUnavailableException(e);
        }
    }

    public void clearCart(String bearerToken) {
        try {
            cartRestClient.delete()
                    .uri("/cart")
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new CartServiceUnavailableException(e);
        }
    }
}
