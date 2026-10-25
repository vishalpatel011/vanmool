package com.vanmool.paymentservice.controllers;

import com.vanmool.paymentservice.configs.SecurityConfig;
import com.vanmool.paymentservice.dtos.PaymentDetailsDto;
import com.vanmool.paymentservice.dtos.PaymentResponseDto;
import com.vanmool.paymentservice.dtos.ReconciliationReportDto;
import com.vanmool.paymentservice.exceptions.OrderNotPayableException;
import com.vanmool.paymentservice.gateways.GatewayUnavailableException;
import com.vanmool.paymentservice.gateways.InvalidSignatureException;
import com.vanmool.paymentservice.models.GatewayName;
import com.vanmool.paymentservice.models.PaymentStatus;
import com.vanmool.paymentservice.security.RestSecurityErrorHandler;
import com.vanmool.paymentservice.services.Customer;
import com.vanmool.paymentservice.services.PaymentService;
import com.vanmool.paymentservice.services.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test with the real security rules: which endpoints need a token or the ADMIN role, how requests and
// errors look. The services are mocks
@WebMvcTest({PaymentController.class, GatewayController.class})
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class PaymentControllerWebMvcTest {
    private static final UUID USER_ID = UUID.fromString("a8c1e6ff-556d-4dd2-a238-148b3fca351c");
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID PAYMENT_ID = UUID.fromString("3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10");
    private static final PaymentResponseDto PAYMENT = new PaymentResponseDto(PAYMENT_ID, ORDER_ID,
            new BigDecimal("999.98"), "INR", PaymentStatus.CREATED, GatewayName.RAZORPAY, "https://rzp.io/i/Test",
            Instant.parse("2026-10-26T10:30:00Z"), Instant.parse("2026-10-26T10:00:00Z"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private ReconciliationService reconciliationService;

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(USER_ID.toString()).claim("email", "asha@example.com").tokenValue("eyJ.token"));
    }

    @Nested
    @DisplayName("POST /payments")
    class Start {
        @Test
        void newLink_returns201WithItsLocation_forTheTokensCustomer() throws Exception {
            // Arrange
            when(paymentService.startPayment(new Customer(USER_ID, "asha@example.com", "eyJ.token"), ORDER_ID, GatewayName.STRIPE))
                    .thenReturn(new PaymentService.StartedPayment(PAYMENT, true));

            // Act & Assert
            mockMvc.perform(post("/payments").with(customer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\": \"" + ORDER_ID + "\", \"gateway\": \"STRIPE\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/payments/" + PAYMENT_ID))
                    .andExpect(jsonPath("$.paymentUrl").value("https://rzp.io/i/Test"))
                    .andExpect(jsonPath("$.amount").value(999.98))
                    .andExpect(jsonPath("$.status").value("CREATED"));
        }

        @Test
        void theOrdersOpenLink_returns200() throws Exception {
            when(paymentService.startPayment(any(), eq(ORDER_ID), isNull())).thenReturn(new PaymentService.StartedPayment(PAYMENT, false));

            mockMvc.perform(post("/payments").with(customer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\": \"" + ORDER_ID + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(PAYMENT_ID.toString()));
        }

        @Test
        void noToken_returns401() throws Exception {
            mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{\"orderId\": \"" + ORDER_ID + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
            verifyNoInteractions(paymentService);
        }

        @Test
        void missingOrderId_orUnknownGateway_returns400() throws Exception {
            mockMvc.perform(post("/payments").with(customer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("orderId: must not be null"));
            mockMvc.perform(post("/payments").with(customer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\": \"" + ORDER_ID + "\", \"gateway\": \"PAYPAL\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
            verifyNoInteractions(paymentService);
        }

        @Test
        void orderNotPayable_returns409_andGatewaysDown_returns503() throws Exception {
            when(paymentService.startPayment(any(), eq(ORDER_ID), isNull()))
                    .thenThrow(new OrderNotPayableException(ORDER_ID, "CANCELLED"))
                    .thenThrow(new GatewayUnavailableException("all down", null));

            mockMvc.perform(post("/payments").with(customer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\": \"" + ORDER_ID + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("ORDER_NOT_PAYABLE"));
            mockMvc.perform(post("/payments").with(customer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\": \"" + ORDER_ID + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("PAYMENT_GATEWAY_UNAVAILABLE"));
        }
    }

    @Test
    void getPayment_returnsThePaymentAndItsHistory() throws Exception {
        when(paymentService.getPayment(USER_ID, PAYMENT_ID)).thenReturn(new PaymentDetailsDto(PAYMENT, List.of(
                new PaymentDetailsDto.StatusChange("CREATED", "API", "Payment link created at RAZORPAY", Instant.parse("2026-10-26T10:00:00Z")))));

        mockMvc.perform(get("/payments/{id}", PAYMENT_ID).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.history[0].source").value("API"));
    }

    @Nested
    @DisplayName("POST /payments/reconciliation")
    class Reconciliation {
        @Test
        void customer_isForbidden() throws Exception {
            mockMvc.perform(post("/payments/reconciliation").with(customer().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
            verifyNoInteractions(reconciliationService);
        }

        @Test
        void admin_runsItAndGetsTheReport() throws Exception {
            when(reconciliationService.reconcile()).thenReturn(new ReconciliationReportDto(4, 1, 0, 0));

            mockMvc.perform(post("/payments/reconciliation").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checked").value(4))
                    .andExpect(jsonPath("$.changed").value(1));
        }
    }

    @Nested
    @DisplayName("Gateway endpoints (no token)")
    class Gateways {
        @Test
        void webhook_passesTheExactBodyAndHeadersOn_andAnswers200() throws Exception {
            // Arrange: spacing and key order kept exactly, as the signature covers the raw body
            String body = "{\"event\":  \"payment_link.paid\" ,\"payload\":{}}";
            ArgumentCaptor<String> received = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);

            // Act
            mockMvc.perform(post("/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Razorpay-Signature", "abc123").content(body))
                    .andExpect(status().isOk());

            // Assert
            verify(paymentService).handleWebhook(eq(GatewayName.RAZORPAY), received.capture(), headers.capture());
            assertEquals(body, received.getValue());
            assertEquals("abc123", headers.getValue().getFirst("X-Razorpay-Signature"));
        }

        @Test
        void webhookWithABadSignature_returns400_withoutSayingMore() throws Exception {
            doThrow(new InvalidSignatureException("Razorpay webhook signature does not match"))
                    .when(paymentService).handleWebhook(eq(GatewayName.RAZORPAY), any(), any());

            mockMvc.perform(post("/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"))
                    .andExpect(jsonPath("$.message").value("The request could not be verified"));
        }

        @Test
        void callback_returnsThePaymentAsTheGatewayReportsIt() throws Exception {
            when(paymentService.handleCallback(GatewayName.STRIPE, Map.of("session_id", "cs_test_Abc"))).thenReturn(PAYMENT);

            mockMvc.perform(get("/payments/callback/stripe").param("session_id", "cs_test_Abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()));
        }
    }
}
