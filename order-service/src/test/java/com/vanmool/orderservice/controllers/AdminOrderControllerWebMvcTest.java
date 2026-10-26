package com.vanmool.orderservice.controllers;

import com.vanmool.orderservice.configs.SecurityConfig;
import com.vanmool.orderservice.dtos.OrderItemResponseDto;
import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.ShipmentDto;
import com.vanmool.orderservice.dtos.ShippingAddressDto;
import com.vanmool.orderservice.dtos.StatusChangeDto;
import com.vanmool.orderservice.exceptions.InvalidOrderStateException;
import com.vanmool.orderservice.exceptions.OrderNotFoundException;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.security.RestSecurityErrorHandler;
import com.vanmool.orderservice.services.FulfilmentService;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The operations team's endpoints with the real security rules: a customer's token is refused before any order is
// touched. FulfilmentService is a mock.
@WebMvcTest(AdminOrderController.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class AdminOrderControllerWebMvcTest {
    private static final String ADMIN_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final String SHIP_REQUEST = """
            { "carrier": "India Post", "trackingNumber": "EE123456789IN" }""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private FulfilmentService fulfilmentService;

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject(ADMIN_ID)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject("a8c1e6ff-556d-4dd2-a238-148b3fca351c"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static OrderResponseDto shippedOrder() {
        Instant placed = Instant.parse("2026-10-26T10:00:00Z");
        return new OrderResponseDto(ORDER_ID, OrderStatus.SHIPPED,
                List.of(new OrderItemResponseDto(UUID.randomUUID(), "Handcrafted Bamboo Basket", new BigDecimal("499.99"),
                        2, new BigDecimal("999.98"))),
                new BigDecimal("999.98"),
                new ShippingAddressDto("Asha Gamit", "9876543210", "12 Station Road", null, "Ahwa", "Gujarat", "394710"),
                new ShipmentDto("India Post", "EE123456789IN"),
                List.of(new StatusChangeDto(OrderStatus.PENDING_PAYMENT, placed, null),
                        new StatusChangeDto(OrderStatus.CONFIRMED, placed.plusSeconds(120), "Paid, payment 1"),
                        new StatusChangeDto(OrderStatus.SHIPPED, placed.plusSeconds(86400), "India Post, tracking EE123456789IN")),
                placed);
    }

    @Test
    void customersToken_isRefusedEverywhereUnderAdmin_andNoOrderIsTouched() throws Exception {
        mockMvc.perform(get("/admin/orders").with(customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mockMvc.perform(post("/admin/orders/{id}/ship", ORDER_ID).with(customer())
                        .contentType(MediaType.APPLICATION_JSON).content(SHIP_REQUEST))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/orders/{id}/deliver", ORDER_ID).with(customer()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fulfilmentService);
    }

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/admin/orders")).andExpect(status().isUnauthorized());
        verifyNoInteractions(fulfilmentService);
    }

    // The real converter: user-auth-service's "roles" claim becomes the roles hasRole("ADMIN") checks
    @Test
    void rolesClaimOfTheToken_becomesSpringRoles() {
        Jwt token = Jwt.withTokenValue("eyJ.admin.token").header("alg", "RS256").subject(ADMIN_ID)
                .claim("roles", List.of("CUSTOMER", "ADMIN")).build();

        List<String> authorities = jwtAuthenticationConverter.convert(token).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).sorted().toList();

        assertEquals(List.of("ROLE_ADMIN", "ROLE_CUSTOMER"), authorities);
    }

    @Nested
    @DisplayName("GET /admin/orders")
    class ListOrders {
        @Test
        void withoutAStatus_listsTheConfirmedOrders_toBeSent() throws Exception {
            when(fulfilmentService.getOrders(eq(OrderStatus.CONFIRMED), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(shippedOrder()), PageRequest.of(0, 20), 1));

            mockMvc.perform(get("/admin/orders").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(ORDER_ID.toString()))
                    .andExpect(jsonPath("$.page.size").value(20));
        }

        @Test
        void unknownStatus_returns400() throws Exception {
            mockMvc.perform(get("/admin/orders").param("status", "SENT").with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
            verifyNoInteractions(fulfilmentService);
        }
    }

    @Nested
    @DisplayName("POST /admin/orders/{id}/ship and /deliver")
    class ShipAndDeliver {
        @Test
        void ship_returnsTheOrderWithItsShipmentAndTimeline() throws Exception {
            // Arrange: the admin's id goes to the service, for the log
            when(fulfilmentService.ship(ADMIN_ID, ORDER_ID, "India Post", "EE123456789IN")).thenReturn(shippedOrder());

            // Act & Assert
            mockMvc.perform(post("/admin/orders/{id}/ship", ORDER_ID).with(admin())
                            .contentType(MediaType.APPLICATION_JSON).content(SHIP_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SHIPPED"))
                    .andExpect(jsonPath("$.shipment.carrier").value("India Post"))
                    .andExpect(jsonPath("$.shipment.trackingNumber").value("EE123456789IN"))
                    .andExpect(jsonPath("$.statusHistory[2].status").value("SHIPPED"))
                    .andExpect(jsonPath("$.statusHistory[2].changedAt").value("2026-10-27T10:00:00Z"));
        }

        @Test
        void shipWithoutATrackingNumber_isAccepted() throws Exception {
            when(fulfilmentService.ship(ADMIN_ID, ORDER_ID, "Dangs Artisan Cooperative", null)).thenReturn(shippedOrder());

            mockMvc.perform(post("/admin/orders/{id}/ship", ORDER_ID).with(admin())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    { "carrier": "Dangs Artisan Cooperative" }"""))
                    .andExpect(status().isOk());
            verify(fulfilmentService).ship(ADMIN_ID, ORDER_ID, "Dangs Artisan Cooperative", null);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "no carrier          | { \"trackingNumber\": \"EE123456789IN\" }               | carrier: must not be blank",
                "blank carrier       | { \"carrier\": \"  \" }                                   | carrier: must not be blank",
                "tracking with space | { \"carrier\": \"India Post\", \"trackingNumber\": \"EE 1\" } | trackingNumber: may contain only letters, digits and hyphens"
        })
        void invalidShipment_returns400NamingTheField(String caseName, String body, String message) throws Exception {
            mockMvc.perform(post("/admin/orders/{id}/ship", ORDER_ID).with(admin())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(message));
            verifyNoInteractions(fulfilmentService);
        }

        @Test
        void orderNotReadyToShip_returns409() throws Exception {
            when(fulfilmentService.ship(ADMIN_ID, ORDER_ID, "India Post", "EE123456789IN"))
                    .thenThrow(new InvalidOrderStateException("An order that is PENDING_PAYMENT cannot be shipped"));

            mockMvc.perform(post("/admin/orders/{id}/ship", ORDER_ID).with(admin())
                            .contentType(MediaType.APPLICATION_JSON).content(SHIP_REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INVALID_ORDER_STATE"))
                    .andExpect(jsonPath("$.message").value("An order that is PENDING_PAYMENT cannot be shipped"));
        }

        @Test
        void deliver_returnsTheOrder_and404ForAnUnknownOne() throws Exception {
            UUID unknown = UUID.randomUUID();
            when(fulfilmentService.markDelivered(ADMIN_ID, ORDER_ID)).thenReturn(shippedOrder());
            when(fulfilmentService.markDelivered(ADMIN_ID, unknown)).thenThrow(new OrderNotFoundException(unknown));

            mockMvc.perform(post("/admin/orders/{id}/deliver", ORDER_ID).with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
            mockMvc.perform(post("/admin/orders/{id}/deliver", unknown).with(admin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
        }
    }
}
