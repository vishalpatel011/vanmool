package com.vanmool.orderservice.clients;

import com.vanmool.orderservice.exceptions.CartServiceUnavailableException;
import com.vanmool.orderservice.exceptions.InsufficientStockException;
import com.vanmool.orderservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.orderservice.exceptions.ProductUnavailableException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// The calls to cart-service and product-service, against MockRestServiceServer: what is sent (path, token, JSON) and
// how each answer is understood. The responses are the JSON those services really send.
class RemoteClientsTest {
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");

    @Nested
    class Cart {
        private final RestClient.Builder builder = RestClient.builder().baseUrl("http://cart-service");
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final CartClient cartClient = new CartClient(builder.build());

        @Test
        void getCartLines_passesTheCustomersToken_andReadsIdsAndQuantities() {
            // Arrange: cart-service's GET /cart/items
            server.expect(requestTo("http://cart-service/cart/items")).andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer eyJ.customer.token"))
                    .andRespond(withSuccess(
                            "[ { \"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": 2 } ]",
                            MediaType.APPLICATION_JSON));

            // Act & Assert
            assertEquals(List.of(new CartClient.CartLine(BASKET_ID, 2)), cartClient.getCartLines("eyJ.customer.token"));
            server.verify();
        }

        @Test
        void clearCart_sendsDeleteWithTheToken() {
            // Arrange
            server.expect(requestTo("http://cart-service/cart")).andExpect(method(HttpMethod.DELETE))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer eyJ.customer.token"))
                    .andRespond(withNoContent());

            // Act
            cartClient.clearCart("eyJ.customer.token");

            // Assert
            server.verify();
        }

        @Test
        void cartServiceFailing_becomesUnavailable() {
            // Arrange
            server.expect(requestTo("http://cart-service/cart/items")).andRespond(withServerError());

            // Act & Assert
            assertThrows(CartServiceUnavailableException.class, () -> cartClient.getCartLines("eyJ.customer.token"));
        }
    }

    @Nested
    class Product {
        private final RestClient.Builder builder = RestClient.builder().baseUrl("http://product-service");
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final ProductClient productClient = new ProductClient(builder.build());
        private final List<CartClient.CartLine> lines = List.of(new CartClient.CartLine(BASKET_ID, 2));

        private static String error(int status, String code, String message) {
            return "{\"timestamp\": \"2026-10-25T10:00:00Z\", \"status\": " + status + ", \"error\": \"" + code
                    + "\", \"message\": \"" + message + "\", \"path\": \"/stock-reservations\"}";
        }

        @Test
        void reserve_sendsTheOrderIdAndItems_andReadsNamesAndPrices() {
            // Arrange
            // The customer's token goes with it: product-service reserves only for a signed-in user
            server.expect(requestTo("http://product-service/stock-reservations")).andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer eyJ.customer.token"))
                    .andExpect(content().json("""
                            { "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                              "items": [ { "productId": "150dac81-6130-4903-9385-9b682db879b3", "quantity": 2 } ] }"""))
                    .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                            { "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "status": "RESERVED",
                              "items": [ { "productId": "150dac81-6130-4903-9385-9b682db879b3",
                                           "name": "Handcrafted Bamboo Basket", "unitPrice": 499.99, "quantity": 2 } ] }"""));

            // Act
            ProductClient.Reservation reservation = productClient.reserve(ORDER_ID, lines, "eyJ.customer.token");

            // Assert
            assertEquals(List.of(new ProductClient.ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2)),
                    reservation.items());
            server.verify();
        }

        @Test
        void insufficientStock_keepsProductServicesMessage() {
            // Arrange
            server.expect(requestTo("http://product-service/stock-reservations"))
                    .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                            .body(error(409, "INSUFFICIENT_STOCK", "Not enough stock for products [" + BASKET_ID + "]")));

            // Act & Assert
            InsufficientStockException exception = assertThrows(InsufficientStockException.class,
                    () -> productClient.reserve(ORDER_ID, lines, "eyJ.customer.token"));
            assertEquals("Not enough stock for products [" + BASKET_ID + "]", exception.getMessage());
        }

        @Test
        void productRemovedFromTheCatalog_becomesProductUnavailable() {
            // Arrange
            server.expect(requestTo("http://product-service/stock-reservations"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                            .body(error(404, "PRODUCT_NOT_FOUND", "Product with id " + BASKET_ID + " not found")));

            // Act & Assert
            ProductUnavailableException exception = assertThrows(ProductUnavailableException.class,
                    () -> productClient.reserve(ORDER_ID, lines, "eyJ.customer.token"));
            assertEquals("Product with id " + BASKET_ID + " not found", exception.getMessage());
        }

        @Test
        void otherConflict_orServerError_becomesUnavailable() {
            // Arrange
            server.expect(requestTo("http://product-service/stock-reservations"))
                    .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                            .body(error(409, "RESERVATION_CONFLICT", "Order already has a reservation with other items")));
            server.expect(requestTo("http://product-service/stock-reservations")).andRespond(withServerError());

            // Act & Assert
            assertThrows(ProductServiceUnavailableException.class, () -> productClient.reserve(ORDER_ID, lines, "eyJ.customer.token"));
            assertThrows(ProductServiceUnavailableException.class, () -> productClient.reserve(ORDER_ID, lines, "eyJ.customer.token"));
        }

        @Test
        void release_sendsDeleteForTheOrder() {
            // Arrange
            server.expect(requestTo("http://product-service/stock-reservations/" + ORDER_ID))
                    .andExpect(method(HttpMethod.DELETE))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer eyJ.customer.token"))
                    .andRespond(withNoContent());

            // Act
            productClient.release(ORDER_ID, "eyJ.customer.token");

            // Assert
            server.verify();
        }

        @Test
        void releaseFailing_becomesUnavailable() {
            // Arrange
            server.expect(requestTo("http://product-service/stock-reservations/" + ORDER_ID)).andRespond(withServerError());

            // Act & Assert
            assertThrows(ProductServiceUnavailableException.class, () -> productClient.release(ORDER_ID, "eyJ.customer.token"));
        }
    }
}
