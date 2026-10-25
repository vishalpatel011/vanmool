package com.vanmool.cartservice.clients;

import com.vanmool.cartservice.exceptions.ProductNotFoundException;
import com.vanmool.cartservice.exceptions.ProductServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

// Calls product-service over REST. The cart needs the current name and price immediately, so this is a synchronous
// request. product-service owns products: this service never reads its database. Its GET /products/{id} is served
// from its Redis cache, so these calls are cheap.
@Slf4j
@Component
public class ProductClient {
    private final RestClient productRestClient;

    public ProductClient(@Qualifier("productRestClient") RestClient productRestClient) {
        this.productRestClient = productRestClient;
    }

    public ProductDetails getProduct(UUID productId) {
        try {
            return productRestClient.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(ProductDetails.class);
        } catch (HttpClientErrorException.NotFound e) {
            // The product does not exist, or it has been removed from the catalog
            throw new ProductNotFoundException(productId);
        } catch (RestClientException e) {
            // Not reachable, too slow (connect or read timeout), or an error response: the cart cannot be priced now
            log.warn("product-service failed for product {}: {}", productId, e.getMessage());
            throw new ProductServiceUnavailableException("The product catalog is not available right now", e);
        }
    }
}
