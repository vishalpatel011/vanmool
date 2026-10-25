package com.vanmool.paymentservice.gateways;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.paymentservice.models.GatewayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Which gateways a payment tries, in which order; and the fake gateway's link, status and webhook signature
class GatewaySelectionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RazorpayGateway razorpay = new RazorpayGateway("rzp_test_key", "secret", "whsec", "http://localhost:8085", objectMapper);
    private final StripeGateway stripe = new StripeGateway("sk_test_key", "whsec", "http://localhost:8085");
    private final FakeGateway fake = new FakeGateway(true, "fake-secret", "http://localhost:8085", objectMapper);
    private final StripeGateway stripeWithoutKey = new StripeGateway("", "", "http://localhost:8085");

    private static List<GatewayName> names(List<PaymentGateway> gateways) {
        return gateways.stream().map(PaymentGateway::name).toList();
    }

    @Test
    void noChoice_startsWithTheDefault_thenTheOtherEnabledGateways() {
        PaymentGatewaySelector selector = new PaymentGatewaySelector(List.of(razorpay, stripe, fake), GatewayName.RAZORPAY);

        assertEquals(List.of(GatewayName.RAZORPAY, GatewayName.STRIPE, GatewayName.FAKE), names(selector.candidates(null)));
    }

    @Test
    void theCustomersChoice_comesFirst() {
        PaymentGatewaySelector selector = new PaymentGatewaySelector(List.of(razorpay, stripe, fake), GatewayName.RAZORPAY);

        assertEquals(List.of(GatewayName.STRIPE, GatewayName.RAZORPAY, GatewayName.FAKE),
                names(selector.candidates(GatewayName.STRIPE)));
    }

    @Test
    void gatewaysWithoutKeys_areNeverTried_andCannotBeChosen() {
        PaymentGatewaySelector selector = new PaymentGatewaySelector(List.of(razorpay, stripeWithoutKey), GatewayName.STRIPE);

        // The default (Stripe) has no key, so only Razorpay is left
        assertEquals(List.of(GatewayName.RAZORPAY), names(selector.candidates(null)));
        assertThrows(GatewayNotEnabledException.class, () -> selector.candidates(GatewayName.STRIPE));
        assertThrows(GatewayNotEnabledException.class, () -> selector.require(GatewayName.STRIPE));
    }

    @Test
    void fakeGateway_givesAnOwnCheckoutLink_andIsPendingUntilCompleted() {
        UUID paymentId = UUID.randomUUID();
        GatewayLink link = fake.createLink(new GatewayPaymentRequest(paymentId, UUID.randomUUID(), 99998, "INR",
                "asha@example.com", "Vanmool order", Instant.now().plusSeconds(1800)));

        assertEquals("fake_" + paymentId, link.reference());
        assertEquals("http://localhost:8085/fake-gateway/checkout/fake_" + paymentId, link.url());
        assertEquals(GatewayOutcome.PENDING, fake.fetchStatus(link.reference()).outcome());
        assertEquals(link.reference(), fake.referenceFromCallback(Map.of("reference", link.reference())));
        assertThrows(InvalidSignatureException.class, () -> fake.referenceFromCallback(Map.of("reference", "fake_unknown")));
    }

    @Test
    void fakeWebhook_isCheckedLikeARealOne() {
        UUID paymentId = UUID.randomUUID();
        String payload = """
                {"id":"fakeevt_1","type":"payment.paid","reference":"fake_x","paymentId":"%s",
                 "gatewayPaymentId":"fakepay_1","amount":99998}""".formatted(paymentId);
        HttpHeaders good = new HttpHeaders();
        good.set("X-Fake-Signature", HmacSha256.hex("fake-secret", payload));
        HttpHeaders bad = new HttpHeaders();
        bad.set("X-Fake-Signature", HmacSha256.hex("wrong-secret", payload));

        GatewayEvent event = fake.parseWebhook(payload, good).orElseThrow();
        assertEquals(paymentId, event.paymentId());
        assertEquals(GatewayOutcome.PAID, event.outcome());
        assertThrows(InvalidSignatureException.class, () -> fake.parseWebhook(payload, bad));
        assertTrue(HmacSha256.matches("abc", "abc"));
    }
}
