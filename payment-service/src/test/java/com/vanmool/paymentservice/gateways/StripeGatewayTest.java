package com.vanmool.paymentservice.gateways;

import com.stripe.model.checkout.Session;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Stripe's webhook check, with Stripe's own Webhook.constructEvent from its SDK. The Stripe-Signature header is built
// here as Stripe builds it: t=<unix time>,v1=HMAC-SHA256("<t>.<body>", webhook secret)
class StripeGatewayTest {
    private static final String WEBHOOK_SECRET = "whsec_test_secret";
    private static final UUID PAYMENT_ID = UUID.fromString("3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10");

    private final StripeGateway gateway = new StripeGateway("sk_test_key", WEBHOOK_SECRET, "http://localhost:8085");

    private static String event(String type, String paymentStatus, String status) {
        return """
                {"id":"evt_Test123","object":"event","type":"%s","api_version":"2025-09-30.clover","created":1792950000,
                 "data":{"object":{"id":"cs_test_Abc","object":"checkout.session","client_reference_id":"%s",
                                   "payment_status":"%s","status":"%s","amount_total":99998,"currency":"inr",
                                   "payment_intent":"pi_Test456"}}}""".formatted(type, PAYMENT_ID, paymentStatus, status);
    }

    private static HttpHeaders signed(String payload, long timestamp, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Stripe-Signature", "t=" + timestamp + ",v1=" + HmacSha256.hex(secret, timestamp + "." + payload));
        return headers;
    }

    private static HttpHeaders signedNow(String payload) {
        return signed(payload, Instant.now().getEpochSecond(), WEBHOOK_SECRET);
    }

    @Test
    void completedPaidSession_becomesAPaidEventForOurPayment() {
        String payload = event("checkout.session.completed", "paid", "complete");

        assertEquals(new GatewayEvent("evt_Test123", "checkout.session.completed", PAYMENT_ID, GatewayOutcome.PAID,
                "pi_Test456", 99998L), gateway.parseWebhook(payload, signedNow(payload)).orElseThrow());
    }

    @Test
    void completedButUnpaidSession_waitsForTheAsyncResult() {
        // A slower payment method: the session is complete, the money is not there yet
        String payload = event("checkout.session.completed", "unpaid", "complete");
        assertTrue(gateway.parseWebhook(payload, signedNow(payload)).isEmpty());

        String succeeded = event("checkout.session.async_payment_succeeded", "paid", "complete");
        String failed = event("checkout.session.async_payment_failed", "unpaid", "complete");
        assertEquals(GatewayOutcome.PAID, gateway.parseWebhook(succeeded, signedNow(succeeded)).orElseThrow().outcome());
        assertEquals(GatewayOutcome.FAILED, gateway.parseWebhook(failed, signedNow(failed)).orElseThrow().outcome());
    }

    @Test
    void expiredSession_becomesExpired_andOtherEventsAreIgnored() {
        String expired = event("checkout.session.expired", "unpaid", "expired");
        String other = event("customer.created", "unpaid", "open");

        assertEquals(GatewayOutcome.EXPIRED, gateway.parseWebhook(expired, signedNow(expired)).orElseThrow().outcome());
        assertTrue(gateway.parseWebhook(other, signedNow(other)).isEmpty());
    }

    @Test
    void wrongSecret_orChangedBody_isRefused() {
        String payload = event("checkout.session.completed", "paid", "complete");
        HttpHeaders wrongSecret = signed(payload, Instant.now().getEpochSecond(), "whsec_someone_else");
        HttpHeaders original = signedNow(payload);

        assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(payload, wrongSecret));
        assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(payload.replace("99998", "1"), original));
        assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(payload, new HttpHeaders()));
    }

    @Test
    void correctlySignedButOldEvent_isRefused_soItCannotBeReplayed() {
        // Signed an hour ago: beyond Stripe's 5-minute tolerance
        String payload = event("checkout.session.completed", "paid", "complete");
        HttpHeaders old = signed(payload, Instant.now().minusSeconds(3600).getEpochSecond(), WEBHOOK_SECRET);

        assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(payload, old));
    }

    @Test
    void sessionStatus_isTranslated() {
        Session paid = ApiResource.GSON.fromJson("""
                {"id":"cs_test_Abc","object":"checkout.session","payment_status":"paid","status":"complete",
                 "amount_total":99998,"payment_intent":"pi_Test456"}""", Session.class);
        Session expired = ApiResource.GSON.fromJson("""
                {"id":"cs_test_Abc","object":"checkout.session","payment_status":"unpaid","status":"expired"}""", Session.class);
        Session open = ApiResource.GSON.fromJson("""
                {"id":"cs_test_Abc","object":"checkout.session","payment_status":"unpaid","status":"open"}""", Session.class);

        assertEquals(new GatewayStatus(GatewayOutcome.PAID, "pi_Test456", 99998L), StripeGateway.statusOf(paid));
        assertEquals(GatewayOutcome.EXPIRED, StripeGateway.statusOf(expired).outcome());
        assertEquals(GatewayOutcome.PENDING, StripeGateway.statusOf(open).outcome());
    }

    @Test
    void callback_namesTheSession_andNeedsOne() {
        assertEquals("cs_test_Abc", gateway.referenceFromCallback(Map.of("session_id", "cs_test_Abc")));
        assertThrows(InvalidSignatureException.class, () -> gateway.referenceFromCallback(Map.of()));
    }
}
