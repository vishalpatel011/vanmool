package com.vanmool.paymentservice.gateways;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.paymentservice.models.GatewayName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// A gateway that needs no account, for development, demos and tests (payment.fake.enabled; never in a deployment).
// It behaves like the real ones from this service's point of view: a checkout page (FakeGatewayController), then a
// webhook signed with HMAC-SHA256 that goes through the same verification and processing as Razorpay's, and a
// status that callbacks and reconciliation can ask for. Its state lives in memory only.
@Slf4j
@Component
public class FakeGateway implements PaymentGateway {
    static final String SIGNATURE_HEADER = "X-Fake-Signature";

    private record Checkout(UUID paymentId, long amountInMinorUnits, GatewayOutcome outcome, String gatewayPaymentId) {
    }

    private final boolean enabled;
    private final String webhookSecret;
    private final String publicBaseUrl;
    private final String webhookUrl;
    private final ObjectMapper objectMapper;
    private final Map<String, Checkout> checkouts = new ConcurrentHashMap<>();

    public FakeGateway(@Value("${payment.fake.enabled}") boolean enabled,
                       @Value("${payment.fake.webhook-secret}") String webhookSecret,
                       @Value("${payment.public-base-url}") String publicBaseUrl,
                       @Value("${payment.fake.webhook-url}") String webhookUrl, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.webhookSecret = webhookSecret;
        this.publicBaseUrl = publicBaseUrl;
        this.webhookUrl = webhookUrl;
        this.objectMapper = objectMapper;
        if (enabled) {
            log.warn("The FAKE payment gateway is enabled: payments can be marked paid without money. Development only");
        }
    }

    @Override
    public GatewayName name() {
        return GatewayName.FAKE;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public GatewayLink createLink(GatewayPaymentRequest request) {
        String reference = "fake_" + request.paymentId();
        checkouts.putIfAbsent(reference, new Checkout(request.paymentId(), request.amountInMinorUnits(),
                GatewayOutcome.PENDING, null));
        return new GatewayLink(reference, publicBaseUrl + "/fake-gateway/checkout/" + reference);
    }

    @Override
    public GatewayStatus fetchStatus(String gatewayReference) {
        Checkout checkout = checkouts.get(gatewayReference);
        if (checkout == null) {
            return new GatewayStatus(GatewayOutcome.PENDING, null, null);
        }
        return new GatewayStatus(checkout.outcome(), checkout.gatewayPaymentId(), checkout.amountInMinorUnits());
    }

    @Override
    public void cancelLink(String gatewayReference) {
        Checkout checkout = checkouts.get(gatewayReference);
        if (checkout != null && checkout.outcome() == GatewayOutcome.PAID) {
            throw new GatewayUnavailableException("The fake payment link is already paid", null);
        }
        if (checkout != null) {
            checkouts.put(gatewayReference, new Checkout(checkout.paymentId(), checkout.amountInMinorUnits(),
                    GatewayOutcome.EXPIRED, null));
        }
    }

    // The customer pays (or the payment fails) on the fake checkout page: record it, then send the signed webhook
    // to this service's webhook endpoint, as a real gateway would
    public Optional<UUID> complete(String reference, boolean success) {
        Checkout checkout = checkouts.get(reference);
        // Like a real gateway, a link that was closed or already finished cannot be paid
        if (checkout == null || checkout.outcome() != GatewayOutcome.PENDING) {
            return Optional.empty();
        }
        GatewayOutcome outcome = success ? GatewayOutcome.PAID : GatewayOutcome.FAILED;
        String gatewayPaymentId = success ? "fakepay_" + UUID.randomUUID().toString().substring(0, 8) : null;
        checkouts.put(reference, new Checkout(checkout.paymentId(), checkout.amountInMinorUnits(), outcome, gatewayPaymentId));
        String payload = objectMapper.createObjectNode()
                .put("id", "fakeevt_" + UUID.randomUUID())
                .put("type", success ? "payment.paid" : "payment.failed")
                .put("reference", reference)
                .put("paymentId", checkout.paymentId().toString())
                .put("gatewayPaymentId", gatewayPaymentId)
                .put("amount", checkout.amountInMinorUnits())
                .toString();
        RestClient.create().post().uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SIGNATURE_HEADER, HmacSha256.hex(webhookSecret, payload))
                .body(payload)
                .retrieve()
                .toBodilessEntity();
        return Optional.of(checkout.paymentId());
    }

    @Override
    public Optional<GatewayEvent> parseWebhook(String payload, HttpHeaders headers) {
        if (!HmacSha256.matches(HmacSha256.hex(webhookSecret, payload), headers.getFirst(SIGNATURE_HEADER))) {
            throw new InvalidSignatureException("Fake gateway webhook signature does not match");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new InvalidSignatureException("Fake gateway webhook is not JSON");
        }
        GatewayOutcome outcome = "payment.paid".equals(event.path("type").asText()) ? GatewayOutcome.PAID : GatewayOutcome.FAILED;
        return Optional.of(new GatewayEvent(event.path("id").asText(), event.path("type").asText(),
                RazorpayGateway.uuidOrNull(event.path("paymentId").asText()), outcome,
                event.path("gatewayPaymentId").asText(null), event.path("amount").asLong()));
    }

    @Override
    public String referenceFromCallback(Map<String, String> parameters) {
        String reference = parameters.get("reference");
        if (reference == null || !checkouts.containsKey(reference)) {
            throw new InvalidSignatureException("Unknown fake gateway reference");
        }
        return reference;
    }
}
