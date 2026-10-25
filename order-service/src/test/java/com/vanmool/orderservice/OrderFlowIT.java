package com.vanmool.orderservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.orderservice.clients.CartClient;
import com.vanmool.orderservice.clients.ProductClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The whole service, from HTTP request to order_db and back. cart-service and product-service are replaced by
// mocks of their clients, so the test needs only the MySQL in DB_URL. Rows of the test's random user are removed afterwards.
@SpringBootTest
@AutoConfigureMockMvc
class OrderFlowIT {
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final String REQUEST = """
            { "shippingAddress": { "name": "Asha Gamit", "phone": "9876543210", "line1": "12 Station Road",
                                   "city": "Ahwa", "state": "Gujarat", "pincode": "394710" } }""";

    private final UUID userId = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CartClient cartClient;

    @MockitoBean
    private ProductClient productClient;

    private RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(userId.toString()).tokenValue("eyJ.customer.token"));
    }

    @BeforeEach
    void cartWithTwoBaskets() {
        when(cartClient.getCartLines("eyJ.customer.token")).thenReturn(List.of(new CartClient.CartLine(BASKET_ID, 2)));
        when(productClient.reserve(any(UUID.class), any())).thenAnswer(invocation -> new ProductClient.Reservation(
                invocation.getArgument(0),
                List.of(new ProductClient.ReservedItem(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2))));
    }

    @AfterEach
    void removeOrders() {
        jdbcTemplate.update("DELETE i FROM order_item i JOIN orders o ON o.id = i.order_id WHERE o.user_id = UUID_TO_BIN(?)", userId.toString());
        jdbcTemplate.update("DELETE FROM orders WHERE user_id = UUID_TO_BIN(?)", userId.toString());
    }

    private String placeOrder(String idempotencyKey) throws Exception {
        var request = post("/orders").with(customer()).contentType(MediaType.APPLICATION_JSON).content(REQUEST);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode order = objectMapper.readTree(body);
        return order.get("id").asText();
    }

    @Test
    void placeReadListAndCancel() throws Exception {
        // Place
        String orderId = placeOrder(null);
        verify(cartClient).clearCart("eyJ.customer.token");

        // Read it back from the database
        mockMvc.perform(get("/orders/{id}", orderId).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(999.98))
                .andExpect(jsonPath("$.items[0].name").value("Handcrafted Bamboo Basket"))
                .andExpect(jsonPath("$.shippingAddress.pincode").value("394710"));

        // Order history
        mockMvc.perform(get("/orders").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].items", hasSize(1)));

        // Cancel: the stock is released, and a second cancel is harmless
        mockMvc.perform(post("/orders/{id}/cancel", orderId).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/orders/{id}/cancel", orderId).with(customer()))
                .andExpect(status().isOk());
        verify(productClient, times(2)).release(UUID.fromString(orderId));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = UUID_TO_BIN(?)", String.class, orderId));
    }

    @Test
    void sameIdempotencyKeyTwice_placesOneOrder() throws Exception {
        // Act
        String first = placeOrder("checkout-it-1");
        String second = placeOrder("checkout-it-1");

        // Assert: one row, one reservation
        assertEquals(first, second);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = UUID_TO_BIN(?)", Integer.class, userId.toString()));
        verify(productClient, times(1)).reserve(any(UUID.class), any());
    }

    @Test
    void anotherCustomer_cannotSeeOrCancelTheOrder() throws Exception {
        // Arrange
        String orderId = placeOrder(null);
        RequestPostProcessor someoneElse = jwt().jwt(token -> token.subject(UUID.randomUUID().toString()));

        // Act & Assert
        mockMvc.perform(get("/orders/{id}", orderId).with(someoneElse)).andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/{id}/cancel", orderId).with(someoneElse)).andExpect(status().isNotFound());
        verify(productClient, times(0)).release(eq(UUID.fromString(orderId)));
    }
}
