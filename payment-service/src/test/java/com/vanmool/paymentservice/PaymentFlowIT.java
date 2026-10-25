package com.vanmool.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.paymentservice.clients.OrderClient;
import com.vanmool.paymentservice.gateways.FakeGateway;
import com.vanmool.paymentservice.models.Payment;
import com.vanmool.paymentservice.models.PaymentStatusChange;
import com.vanmool.paymentservice.repositories.PaymentRepository;
import com.vanmool.paymentservice.services.PaymentService;
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
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The payment flow against MySQL (payment_db) and Kafka, with the fake gateway. The service runs on a real port,
// because the fake gateway sends its signed webhook over HTTP, as Razorpay or Stripe would. order-service is
// replaced by a mock of its client; the customer's requests go through MockMvc with jwt().
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18085",
        "payment.public-base-url=http://localhost:18085",
        "payment.fake.enabled=true",
        "payment.default-gateway=FAKE",
        "payment.razorpay.key-id=", "payment.stripe.secret-key="})
@AutoConfigureMockMvc
class PaymentFlowIT {
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final RestClient http = RestClient.builder().baseUrl("http://localhost:18085").build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private FakeGateway fakeGateway;

    @MockitoBean
    private OrderClient orderClient;

    @BeforeEach
    void orderWaitingForPayment() {
        when(orderClient.getOrder(eq(orderId), any())).thenReturn(new OrderClient.Order(orderId, "PENDING_PAYMENT", new BigDecimal("999.98")));
    }

    @AfterEach
    void removeTestRows() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE message_key = ?", orderId.toString());
        jdbcTemplate.update("DELETE c FROM payment_status_change c JOIN payments p ON p.id = c.payment_id WHERE p.order_id = UUID_TO_BIN(?)", orderId.toString());
        jdbcTemplate.update("DELETE FROM payments WHERE order_id = UUID_TO_BIN(?)", orderId.toString());
    }

    // expectedStatus 0: any success (201 for a new link, 200 for the open one)
    private JsonNode startPayment(int expectedStatus) throws Exception {
        var result = mockMvc.perform(post("/payments")
                        .with(jwt().jwt(token -> token.subject(userId.toString()).claim("email", "it@example.com")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\": \"" + orderId + "\"}"))
                .andExpect(expectedStatus == 0 ? status().is2xxSuccessful() : status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> history(UUID paymentId) {
        return jdbcTemplate.queryForList("SELECT CONCAT(status, '/', source) FROM payment_status_change "
                + "WHERE payment_id = UUID_TO_BIN(?) ORDER BY recorded_at", String.class, paymentId.toString());
    }

    @Test
    void pay_throughTheGatewaysCheckout_andItsSignedWebhook() throws Exception {
        // Start: a link at the fake gateway, for the amount order-service gave
        JsonNode payment = startPayment(201);
        UUID paymentId = UUID.fromString(payment.get("id").asText());
        assertEquals("http://localhost:18085/fake-gateway/checkout/fake_" + paymentId, payment.get("paymentUrl").asText());
        assertEquals(999.98, payment.get("amount").asDouble());

        // Asking again returns the same open link (200)
        assertEquals(paymentId.toString(), startPayment(200).get("id").asText());

        // The customer pays on the checkout page; the gateway sends its webhook, then sends the browser to the callback
        var redirect = http.get().uri("/fake-gateway/checkout/fake_{id}/complete?result=success", paymentId)
                .retrieve().toBodilessEntity();
        assertEquals(302, redirect.getStatusCode().value());
        assertEquals("/payments/callback/fake?reference=fake_" + paymentId, redirect.getHeaders().getLocation().toString());

        // The callback asks the gateway and shows the result (already PAID through the webhook: no second change)
        JsonNode shown = objectMapper.readTree(http.get().uri("/payments/callback/fake?reference=fake_{id}", paymentId)
                .retrieve().body(String.class));
        assertEquals("PAID", shown.get("status").asText());

        // Assert: PAID once, with the whole history, the event for order-service, and the gateway's event id stored
        Payment paid = paymentRepository.findById(paymentId).orElseThrow();
        assertEquals("PAID", paid.getStatus().name());
        assertEquals(List.of("CREATED/API", "CREATED/API", "PAID/WEBHOOK"), history(paymentId));
        assertEquals(List.of("PaymentSucceeded"), jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_event WHERE message_key = ?", String.class, orderId.toString()));
    }

    @Test
    void sameWebhookTwice_isHandledOnce_andAForgedOneIsRefused() throws Exception {
        // Arrange
        UUID paymentId = UUID.fromString(startPayment(201).get("id").asText());
        String payload = """
                {"id":"fakeevt_dup_%s","type":"payment.paid","reference":"fake_%s","paymentId":"%s",
                 "gatewayPaymentId":"fakepay_dup","amount":99998}""".formatted(paymentId, paymentId, paymentId);
        String signature = hmac(payload);

        // Act: the same signed delivery twice, then a forged one
        for (int i = 0; i < 2; i++) {
            assertEquals(200, http.post().uri("/payments/webhooks/fake").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Fake-Signature", signature).body(payload).retrieve().toBodilessEntity().getStatusCode().value());
        }
        int forged = http.post().uri("/payments/webhooks/fake").contentType(MediaType.APPLICATION_JSON)
                .header("X-Fake-Signature", "0".repeat(64)).body(payload.replace("99998", "1"))
                .exchange((request, response) -> response.getStatusCode().value());

        // Assert
        assertEquals(400, forged);
        assertEquals(List.of("CREATED/API", "CREATED/API", "PAID/WEBHOOK"), history(paymentId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE message_key = ?", Integer.class, orderId.toString()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_gateway_event WHERE id = ?", Integer.class, "FAKE:fakeevt_dup_" + paymentId));
        jdbcTemplate.update("DELETE FROM processed_gateway_event WHERE id = ?", "FAKE:fakeevt_dup_" + paymentId);
    }

    @Test
    void twoStartsAtTheSameMoment_giveOnePayment() throws Exception {
        // Act
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<String> start = () -> startPayment(0).get("id").asText();
            results.add(pool.submit(start));
        }
        List<String> ids = new ArrayList<>();
        for (Future<String> result : results) {
            ids.add(result.get());
        }
        pool.shutdown();

        // Assert: every request got the same payment, and only one row is open (the unique index)
        assertEquals(1, ids.stream().distinct().count(), ids.toString());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, orderId.toString()));
    }

    @Test
    void cancelledOrder_closesItsOpenLink() throws Exception {
        // Arrange
        UUID paymentId = UUID.fromString(startPayment(201).get("id").asText());

        // Act: what OrderCancelled from order-service triggers
        paymentService.closeOpenPayment(orderId);

        // Assert: expired, and the fake gateway's link is closed too
        assertEquals("EXPIRED", paymentRepository.findById(paymentId).orElseThrow().getStatus().name());
        assertEquals(List.of("CREATED/API", "CREATED/API", "EXPIRED/ORDER_EVENT"), history(paymentId));
        assertEquals("EXPIRED", fakeGateway.fetchStatus("fake_" + paymentId).outcome().name());
    }

    @Test
    void reconciliation_findsWhatTheGatewayKnows() throws Exception {
        // Arrange: the link was closed at the gateway, but no webhook told us
        UUID paymentId = UUID.fromString(startPayment(201).get("id").asText());
        fakeGateway.cancelLink("fake_" + paymentId);

        // Act
        PaymentService.ApplyResult result = paymentService.reconcile(paymentRepository.findById(paymentId).orElseThrow(),
                PaymentStatusChange.Source.RECONCILIATION);

        // Assert
        assertEquals(PaymentService.ApplyResult.CHANGED, result);
        assertEquals(List.of("CREATED/API", "CREATED/API", "EXPIRED/RECONCILIATION"), history(paymentId));
        await().until(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE message_key = ? "
                + "AND event_type = 'PaymentFailed'", Integer.class, orderId.toString()) == 1);
    }

    private static String hmac(String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("fake-gateway-local-secret".getBytes(), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
