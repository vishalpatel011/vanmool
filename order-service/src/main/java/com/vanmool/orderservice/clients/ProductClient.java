package com.vanmool.orderservice.clients;

import com.vanmool.orderservice.exceptions.InsufficientStockException;
import com.vanmool.orderservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.orderservice.exceptions.ProductUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Reserves and releases stock in product-service. Both calls are keyed by the order id, so repeating either one after
// a timeout is safe: product-service never takes or returns the same order's units twice.
@Slf4j
@Component
public class ProductClient {
    private final RestClient productRestClient;

    public ProductClient(@Qualifier("productRestClient") RestClient productRestClient) {
        this.productRestClient = productRestClient;
    }

    record ReservationItem(UUID productId, int quantity) {
    }

    record ReservationRequest(UUID orderId, List<ReservationItem> items) {
    }

    // What product-service reserved, with the name and price of every product: the order is placed at these prices
    public record ReservedItem(UUID productId, String name, BigDecimal unitPrice, int quantity) {
    }

    public record Reservation(UUID orderId, List<ReservedItem> items) {
    }

    // product-service's error body; only the code and the message are needed
    record RemoteError(String error, String message) {
    }

    // With the customer's token: product-service only reserves for a signed-in user, and the API gateway does not
    // expose these endpoints, so only the services reach them
    public Reservation reserve(UUID orderId, List<CartClient.CartLine> lines, String bearerToken) {
        List<ReservationItem> items = lines.stream()
                .map(line -> new ReservationItem(line.productId(), line.quantity()))
                .toList();
        try {
            return productRestClient.post()
                    .uri("/stock-reservations")
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .body(new ReservationRequest(orderId, items))
                    .retrieve()
                    .body(Reservation.class);
        } catch (HttpClientErrorException e) {
            RemoteError error = e.getResponseBodyAs(RemoteError.class);
            String message = error == null ? e.getStatusText() : error.message();
            if (e.getStatusCode() == HttpStatus.CONFLICT && error != null && "INSUFFICIENT_STOCK".equals(error.error())) {
                throw new InsufficientStockException(message);
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // A product in the cart has been removed from the catalog
                throw new ProductUnavailableException(message);
            }
            log.warn("product-service refused the reservation for order {}: {} {}", orderId, e.getStatusCode(), message);
            throw new ProductServiceUnavailableException(e);
        } catch (RestClientException e) {
            log.warn("product-service failed to reserve stock for order {}: {}", orderId, e.getMessage());
            throw new ProductServiceUnavailableException(e);
        }
    }

    public void release(UUID orderId, String bearerToken) {
        try {
            productRestClient.delete()
                    .uri("/stock-reservations/{orderId}", orderId)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProductServiceUnavailableException(e);
        }
    }
}
