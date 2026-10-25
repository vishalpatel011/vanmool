package com.vanmool.orderservice.controllers;

import com.vanmool.orderservice.configs.SecurityConfig;
import com.vanmool.orderservice.dtos.OrderItemResponseDto;
import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.ShippingAddressDto;
import com.vanmool.orderservice.exceptions.CartEmptyException;
import com.vanmool.orderservice.exceptions.CartServiceUnavailableException;
import com.vanmool.orderservice.exceptions.InsufficientStockException;
import com.vanmool.orderservice.exceptions.InvalidOrderStateException;
import com.vanmool.orderservice.exceptions.OrderNotFoundException;
import com.vanmool.orderservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.orderservice.exceptions.ProductUnavailableException;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.security.RestSecurityErrorHandler;
import com.vanmool.orderservice.services.Customer;
import com.vanmool.orderservice.services.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test with the real security rules and error format. jwt() stands in for a checked bearer token with a
// chosen subject and token value; OrderService is a mock.
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class OrderControllerWebMvcTest {
    private static final UUID USER_ID = UUID.fromString("a8c1e6ff-556d-4dd2-a238-148b3fca351c");
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final Customer CUSTOMER = new Customer(USER_ID, "asha@example.com", "eyJ.customer.token");
    private static final ShippingAddressDto ADDRESS = new ShippingAddressDto("Asha Gamit", "9876543210",
            "12 Station Road", null, "Ahwa", "Gujarat", "394710");
    private static final String VALID_REQUEST = """
            {
              "shippingAddress": {
                "name": "Asha Gamit",
                "phone": "9876543210",
                "line1": "12 Station Road",
                "city": "Ahwa",
                "state": "Gujarat",
                "pincode": "394710"
              }
            }""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private static RequestPostProcessor customerToken() {
        return jwt().jwt(token -> token.subject(USER_ID.toString()).claim("email", "asha@example.com")
                .tokenValue("eyJ.customer.token"));
    }

    private static OrderResponseDto order(OrderStatus status) {
        return new OrderResponseDto(ORDER_ID, status,
                List.of(new OrderItemResponseDto(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2,
                        new BigDecimal("999.98"))),
                new BigDecimal("999.98"), ADDRESS, Instant.parse("2026-10-25T10:00:00Z"));
    }

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
        verifyNoInteractions(orderService);
    }

    @Nested
    @DisplayName("POST /orders")
    class PlaceOrder {
        @Test
        void validRequest_returns201WithTheOrderAndItsLocation() throws Exception {
            // Arrange
            // The customer passed on is the token's: subject, email claim and the token itself
            when(orderService.placeOrder(CUSTOMER, ADDRESS, null)).thenReturn(order(OrderStatus.PENDING_PAYMENT));

            // Act & Assert: STRICT means no field may be missing or extra
            mockMvc.perform(post("/orders").with(customerToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/orders/" + ORDER_ID))
                    .andExpect(content().json("""
                            {
                              "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                              "status": "PENDING_PAYMENT",
                              "items": [
                                {
                                  "productId": "150dac81-6130-4903-9385-9b682db879b3",
                                  "name": "Handcrafted Bamboo Basket",
                                  "unitPrice": 499.99,
                                  "quantity": 2,
                                  "lineTotal": 999.98
                                }
                              ],
                              "totalAmount": 999.98,
                              "shippingAddress": {
                                "name": "Asha Gamit",
                                "phone": "9876543210",
                                "line1": "12 Station Road",
                                "line2": null,
                                "city": "Ahwa",
                                "state": "Gujarat",
                                "pincode": "394710"
                              },
                              "createdAt": "2026-10-25T10:00:00Z"
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void idempotencyKey_isPassedToTheService() throws Exception {
            // Arrange
            when(orderService.placeOrder(any(), any(), eq("checkout-7f3a"))).thenReturn(order(OrderStatus.PENDING_PAYMENT));

            // Act & Assert
            mockMvc.perform(post("/orders").with(customerToken()).header("Idempotency-Key", "checkout-7f3a")
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().isCreated());
            verify(orderService).placeOrder(eq(CUSTOMER), any(), eq("checkout-7f3a"));
        }

        @Test
        void idempotencyKeyWithOtherCharacters_returns400() throws Exception {
            mockMvc.perform(post("/orders").with(customerToken()).header("Idempotency-Key", "not a key!")
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Idempotency-Key: must be 1 to 100 letters, digits, - or _"));
            verifyNoInteractions(orderService);
        }

        @ParameterizedTest(name = "{0} = {1}")
        @CsvSource({
                "phone,   12345,   shippingAddress.phone: must be a 10-digit mobile number",
                "phone,   5876543210, shippingAddress.phone: must be a 10-digit mobile number",
                "pincode, 39471,   shippingAddress.pincode: must be a 6-digit PIN code",
                "pincode, 094710,  shippingAddress.pincode: must be a 6-digit PIN code",
                "city,    '',      shippingAddress.city: must not be blank",
        })
        void invalidAddressField_returns400NamingIt(String field, String value, String message) throws Exception {
            String request = VALID_REQUEST.replaceFirst("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": \"" + value + "\"");
            mockMvc.perform(post("/orders").with(customerToken()).contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(message));
            verifyNoInteractions(orderService);
        }

        @Test
        void missingAddress_returns400() throws Exception {
            mockMvc.perform(post("/orders").with(customerToken()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("shippingAddress: must not be null"));
            verifyNoInteractions(orderService);
        }

        @ParameterizedTest(name = "{1} -> {2} {3}")
        @CsvSource({
                "cart-empty,        CartEmptyException,                 409, CART_EMPTY",
                "stock,             InsufficientStockException,         409, INSUFFICIENT_STOCK",
                "product-gone,      ProductUnavailableException,        409, PRODUCT_UNAVAILABLE",
                "cart-down,         CartServiceUnavailableException,    503, CART_SERVICE_UNAVAILABLE",
                "product-down,      ProductServiceUnavailableException, 503, PRODUCT_SERVICE_UNAVAILABLE",
        })
        void eachCheckoutFailure_hasItsStatusAndCode(String caseName, String exception, int status, String code) throws Exception {
            // Arrange
            RuntimeException thrown = switch (exception) {
                case "CartEmptyException" -> new CartEmptyException();
                case "InsufficientStockException" -> new InsufficientStockException("Not enough stock for products [" + BASKET_ID + "]");
                case "ProductUnavailableException" -> new ProductUnavailableException("Product with id " + BASKET_ID + " not found");
                case "CartServiceUnavailableException" -> new CartServiceUnavailableException(new RuntimeException("down"));
                default -> new ProductServiceUnavailableException(new RuntimeException("down"));
            };
            when(orderService.placeOrder(any(), any(), isNull())).thenThrow(thrown);

            // Act & Assert
            mockMvc.perform(post("/orders").with(customerToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                    .andExpect(status().is(status))
                    .andExpect(jsonPath("$.error").value(code))
                    .andExpect(jsonPath("$.message").value(thrown.getMessage()));
        }
    }

    @Nested
    @DisplayName("GET /orders and GET /orders/{id}")
    class Read {
        @Test
        void orderHistory_isAPageOfTheCustomersOrders() throws Exception {
            // Arrange
            Pageable firstPage = PageRequest.of(0, 10);
            when(orderService.getOrders(eq(USER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(order(OrderStatus.PENDING_PAYMENT)), firstPage, 1));

            // Act & Assert
            mockMvc.perform(get("/orders").with(customerToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(ORDER_ID.toString()))
                    .andExpect(jsonPath("$.page.size").value(10))
                    .andExpect(jsonPath("$.page.totalElements").value(1));
        }

        @Test
        void oneOrder_isReturned() throws Exception {
            // Arrange
            when(orderService.getOrder(USER_ID, ORDER_ID)).thenReturn(order(OrderStatus.PENDING_PAYMENT));

            // Act & Assert
            mockMvc.perform(get("/orders/{id}", ORDER_ID).with(customerToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        }

        @Test
        void unknownOrAnotherCustomersOrder_returns404() throws Exception {
            // Arrange
            when(orderService.getOrder(USER_ID, ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

            // Act & Assert
            mockMvc.perform(get("/orders/{id}", ORDER_ID).with(customerToken()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Order with id " + ORDER_ID + " not found"));
        }

        @Test
        void orderIdThatIsNotAUuid_returns400() throws Exception {
            mockMvc.perform(get("/orders/not-a-uuid").with(customerToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
            verifyNoInteractions(orderService);
        }

        @Test
        void tokenWhoseSubjectIsNotAUserId_returns403() throws Exception {
            mockMvc.perform(get("/orders").with(jwt().jwt(token -> token.subject("some-service"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
            verifyNoInteractions(orderService);
        }
    }

    @Nested
    @DisplayName("POST /orders/{id}/cancel")
    class Cancel {
        @Test
        void pendingOrder_returnsTheCancelledOrder() throws Exception {
            // Arrange
            when(orderService.cancelOrder(CUSTOMER, ORDER_ID)).thenReturn(order(OrderStatus.CANCELLED));

            // Act & Assert
            mockMvc.perform(post("/orders/{id}/cancel", ORDER_ID).with(customerToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        void orderThatCannotBeCancelled_returns409() throws Exception {
            // Arrange
            when(orderService.cancelOrder(CUSTOMER, ORDER_ID))
                    .thenThrow(new InvalidOrderStateException("An order that is CONFIRMED cannot be cancelled"));

            // Act & Assert
            mockMvc.perform(post("/orders/{id}/cancel", ORDER_ID).with(customerToken()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_ORDER_STATE"))
                    .andExpect(jsonPath("$.message").value("An order that is CONFIRMED cannot be cancelled"));
        }
    }
}
