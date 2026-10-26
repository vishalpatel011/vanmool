package com.vanmool.orderservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.orderservice.clients.CartClient;
import com.vanmool.orderservice.clients.ProductClient;
import com.vanmool.orderservice.services.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
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

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private CartClient cartClient;

    @MockitoBean
    private ProductClient productClient;

    private RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(userId.toString()).claim("email", "asha@example.com")
                .tokenValue("eyJ.customer.token"));
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
        jdbcTemplate.update("DELETE FROM outbox_event WHERE JSON_UNQUOTE(payload->'$.userId') = ?", userId.toString());
        jdbcTemplate.update("DELETE c FROM order_status_change c JOIN orders o ON o.id = c.order_id WHERE o.user_id = UUID_TO_BIN(?)", userId.toString());
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

        // Cancel, twice: the second one changes nothing
        mockMvc.perform(post("/orders/{id}/cancel", orderId).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/orders/{id}/cancel", orderId).with(customer()))
                .andExpect(status().isOk());
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = UUID_TO_BIN(?)", String.class, orderId));

        // The order's events were recorded once each, in the same transactions as the changes; the stock is released
        // by product-service from OrderCancelled, not by a call from here
        assertEquals(List.of("OrderPlaced", "OrderCancelled"), jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_event WHERE message_key = ? ORDER BY created_at", String.class, orderId));
        verify(productClient, times(0)).release(any());
    }

    @Test
    void sameIdempotencyKeyTwice_placesOneOrder() throws Exception {
        // Act
        String first = placeOrder("checkout-it-1");
        String second = placeOrder("checkout-it-1");

        // Assert: one row, one reservation, one OrderPlaced
        assertEquals(first, second);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE message_key = ?", Integer.class, first));
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
        assertEquals("PENDING_PAYMENT", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = UUID_TO_BIN(?)", String.class, orderId));
    }

    @Test
    void paidOrder_isShippedAndDelivered_andTheCustomerCanFollowIt() throws Exception {
        // Arrange: a placed order, paid (what PaymentSucceeded does)
        String orderId = placeOrder(null);
        orderService.confirmPayment(UUID.fromString(orderId), UUID.randomUUID());
        RequestPostProcessor admin = jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        // The order is in the team's queue of orders to send; the customer cannot open that queue
        mockMvc.perform(get("/admin/orders").param("size", "50").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + orderId + "')]", hasSize(1)));
        mockMvc.perform(get("/admin/orders").with(customer())).andExpect(status().isForbidden());

        // Ship, then deliver
        mockMvc.perform(post("/admin/orders/{id}/ship", orderId).with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "carrier": "India Post", "trackingNumber": "EE123456789IN" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        mockMvc.perform(get("/orders/{id}", orderId).with(customer()))
                .andExpect(jsonPath("$.shipment.carrier").value("India Post"))
                .andExpect(jsonPath("$.shipment.trackingNumber").value("EE123456789IN"))
                .andExpect(jsonPath("$.statusHistory[*].status", contains("PENDING_PAYMENT", "CONFIRMED", "SHIPPED")))
                .andExpect(jsonPath("$.statusHistory[2].detail").value("India Post, tracking EE123456789IN"));
        mockMvc.perform(post("/admin/orders/{id}/deliver", orderId).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // A delivered order can no longer be cancelled; its timeline is complete
        mockMvc.perform(post("/orders/{id}/cancel", orderId).with(customer())).andExpect(status().isConflict());
        mockMvc.perform(get("/orders").with(customer()))
                .andExpect(jsonPath("$.content[0].statusHistory[*].status",
                        contains("PENDING_PAYMENT", "CONFIRMED", "SHIPPED", "DELIVERED")));

        // One event per change, for notification-service to email the customer
        assertEquals(List.of("OrderPlaced", "OrderShipped", "OrderDelivered"), jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_event WHERE message_key = ? ORDER BY created_at", String.class, orderId));
    }
}
