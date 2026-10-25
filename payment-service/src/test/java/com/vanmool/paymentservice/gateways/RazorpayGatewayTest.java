package com.vanmool.paymentservice.gateways;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Razorpay's webhook and callback checks, with Razorpay's own verification code from its SDK and payloads shaped like
// the ones in Razorpay's documentation. Signatures are computed here with the test secrets, as Razorpay would
class RazorpayGatewayTest {
    private static final String KEY_SECRET = "test_key_secret";
    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final UUID PAYMENT_ID = UUID.fromString("3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10");

    private final RazorpayGateway gateway = new RazorpayGateway("rzp_test_key", KEY_SECRET, WEBHOOK_SECRET,
            "http://localhost:8085", new ObjectMapper());

    private static String paymentLinkPaid() {
        return """
                {"entity":"event","account_id":"acc_Test","event":"payment_link.paid",
                 "contains":["payment_link","order","payment"],
                 "payload":{
                   "payment_link":{"entity":{"id":"plink_Test123","amount":99998,"amount_paid":99998,
                                             "reference_id":"%s","status":"paid","currency":"INR"}},
                   "payment":{"entity":{"id":"pay_Test456","amount":99998,"status":"captured","method":"upi"}}},
                 "created_at":1792950000}""".formatted(PAYMENT_ID);
    }

    private static HttpHeaders signed(String payload, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Razorpay-Signature", HmacSha256.hex(secret, payload));
        headers.set("X-Razorpay-Event-Id", "evt_Test789");
        return headers;
    }

    @Test
    void paidWebhook_withTheRightSignature_becomesAPaidEventForOurPayment() {
        // Act
        Optional<GatewayEvent> event = gateway.parseWebhook(paymentLinkPaid(), signed(paymentLinkPaid(), WEBHOOK_SECRET));

        // Assert
        assertEquals(new GatewayEvent("evt_Test789", "payment_link.paid", PAYMENT_ID, GatewayOutcome.PAID,
                "pay_Test456", 99998L), event.orElseThrow());
    }

    @Test
    void webhookSignedWithAnotherSecret_isRefused() {
        assertThrows(InvalidSignatureException.class,
                () -> gateway.parseWebhook(paymentLinkPaid(), signed(paymentLinkPaid(), "someone_elses_secret")));
    }

    @Test
    void webhookChangedAfterSigning_isRefused() {
        // The amount is changed after Razorpay signed the body
        HttpHeaders headers = signed(paymentLinkPaid(), WEBHOOK_SECRET);
        assertThrows(InvalidSignatureException.class,
                () -> gateway.parseWebhook(paymentLinkPaid().replace("99998", "1"), headers));
    }

    @Test
    void webhookWithoutASignature_isRefused() {
        assertThrows(InvalidSignatureException.class, () -> gateway.parseWebhook(paymentLinkPaid(), new HttpHeaders()));
    }

    @Test
    void expiredAndCancelledLinks_becomeExpiredAndFailed_andAFailedAttemptIsIgnored() {
        String expired = paymentLinkPaid().replace("payment_link.paid", "payment_link.expired");
        String cancelled = paymentLinkPaid().replace("payment_link.paid", "payment_link.cancelled");
        String attemptFailed = paymentLinkPaid().replace("payment_link.paid", "payment.failed");

        assertEquals(GatewayOutcome.EXPIRED, gateway.parseWebhook(expired, signed(expired, WEBHOOK_SECRET)).orElseThrow().outcome());
        assertEquals(GatewayOutcome.FAILED, gateway.parseWebhook(cancelled, signed(cancelled, WEBHOOK_SECRET)).orElseThrow().outcome());
        // One failed attempt does not close the link: the customer can try again on it
        assertTrue(gateway.parseWebhook(attemptFailed, signed(attemptFailed, WEBHOOK_SECRET)).isEmpty());
    }

    @Test
    void callback_withTheRightSignature_namesThePaymentLink() {
        // Arrange: razorpay_signature = HMAC-SHA256(link id|reference|status|payment id, key secret)
        String signature = HmacSha256.hex(KEY_SECRET, "plink_Test123|" + PAYMENT_ID + "|paid|pay_Test456");
        Map<String, String> parameters = Map.of(
                "razorpay_payment_id", "pay_Test456",
                "razorpay_payment_link_id", "plink_Test123",
                "razorpay_payment_link_reference_id", PAYMENT_ID.toString(),
                "razorpay_payment_link_status", "paid",
                "razorpay_signature", signature);

        // Act & Assert
        assertEquals("plink_Test123", gateway.referenceFromCallback(parameters));
    }

    @Test
    void callback_whoseStatusWasChangedInTheUrl_isRefused() {
        // Signed for "cancelled", but the URL says "paid"
        String signature = HmacSha256.hex(KEY_SECRET, "plink_Test123|" + PAYMENT_ID + "|cancelled|pay_Test456");
        Map<String, String> parameters = Map.of(
                "razorpay_payment_id", "pay_Test456",
                "razorpay_payment_link_id", "plink_Test123",
                "razorpay_payment_link_reference_id", PAYMENT_ID.toString(),
                "razorpay_payment_link_status", "paid",
                "razorpay_signature", signature);

        assertThrows(InvalidSignatureException.class, () -> gateway.referenceFromCallback(parameters));
    }

    @Test
    void linkStatus_isTranslated_withTheCapturedPaymentsId() {
        JSONObject paid = new JSONObject("""
                {"id":"plink_Test123","status":"paid","amount":99998,"amount_paid":99998,
                 "payments":[{"payment_id":"pay_Failed1","status":"failed"},{"payment_id":"pay_Test456","status":"captured"}]}""");

        assertEquals(new GatewayStatus(GatewayOutcome.PAID, "pay_Test456", 99998L), RazorpayGateway.statusOf(paid));
        assertEquals(GatewayOutcome.EXPIRED, RazorpayGateway.statusOf(new JSONObject("{\"status\":\"expired\"}")).outcome());
        assertEquals(GatewayOutcome.FAILED, RazorpayGateway.statusOf(new JSONObject("{\"status\":\"cancelled\"}")).outcome());
        assertEquals(GatewayOutcome.PENDING, RazorpayGateway.statusOf(new JSONObject("{\"status\":\"created\"}")).outcome());
    }

    @Test
    void enabled_onlyWithKeys() {
        assertTrue(gateway.isEnabled());
        RazorpayGateway withoutKeys = new RazorpayGateway("", "", "", "http://localhost:8085", new ObjectMapper());
        assertEquals(false, withoutKeys.isEnabled());
        assertNull(RazorpayGateway.uuidOrNull("not-a-uuid"));
    }
}
