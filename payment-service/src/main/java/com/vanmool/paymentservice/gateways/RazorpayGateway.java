package com.vanmool.paymentservice.gateways;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.vanmool.paymentservice.models.GatewayName;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Razorpay Payment Links (UPI, cards, net banking, wallets), through Razorpay's Java SDK.
//   - create: POST /v1/payment_links with our payment id as reference_id. Razorpay refuses a second link with the
//     same reference_id, so a retried request can never create two links for one payment.
//   - webhook: X-Razorpay-Signature = HMAC-SHA256(body, webhook secret); X-Razorpay-Event-Id identifies the event.
//   - callback: the redirect carries razorpay_signature = HMAC-SHA256 of link id | reference | status | payment id,
//     with the key secret.
@Component
public class RazorpayGateway implements PaymentGateway {
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final String publicBaseUrl;
    private final ObjectMapper objectMapper;

    public RazorpayGateway(@Value("${payment.razorpay.key-id}") String keyId,
                           @Value("${payment.razorpay.key-secret}") String keySecret,
                           @Value("${payment.razorpay.webhook-secret}") String webhookSecret,
                           @Value("${payment.public-base-url}") String publicBaseUrl, ObjectMapper objectMapper) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        this.publicBaseUrl = publicBaseUrl;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayName name() {
        return GatewayName.RAZORPAY;
    }

    @Override
    public boolean isEnabled() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    private RazorpayClient client() throws RazorpayException {
        return new RazorpayClient(keyId, keySecret);
    }

    @Override
    public GatewayLink createLink(GatewayPaymentRequest request) {
        JSONObject link = new JSONObject();
        link.put("amount", request.amountInMinorUnits());
        link.put("currency", request.currency());
        link.put("accept_partial", false);
        link.put("reference_id", request.paymentId().toString());
        link.put("description", request.description());
        link.put("expire_by", request.expiresAt().getEpochSecond());
        if (request.customerEmail() != null) {
            link.put("customer", new JSONObject().put("email", request.customerEmail()));
        }
        // Vanmool sends its own emails; Razorpay should not message the customer as well
        link.put("notify", new JSONObject().put("sms", false).put("email", false));
        link.put("reminder_enable", false);
        link.put("notes", new JSONObject().put("orderId", request.orderId().toString()));
        link.put("callback_url", publicBaseUrl + "/payments/callback/razorpay");
        link.put("callback_method", "get");
        try {
            PaymentLink created = client().paymentLink.create(link);
            return new GatewayLink(created.get("id"), created.get("short_url"));
        } catch (RazorpayException | RuntimeException e) {
            throw new GatewayUnavailableException("Razorpay could not create the payment link: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayStatus fetchStatus(String gatewayReference) {
        try {
            JSONObject link = client().paymentLink.fetch(gatewayReference).toJson();
            return statusOf(link);
        } catch (RazorpayException | RuntimeException e) {
            throw new GatewayUnavailableException("Razorpay could not return the payment link: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelLink(String gatewayReference) {
        try {
            client().paymentLink.cancel(gatewayReference);
        } catch (RazorpayException | RuntimeException e) {
            throw new GatewayUnavailableException("Razorpay could not cancel the payment link: " + e.getMessage(), e);
        }
    }

    // Link status: created, partially_paid (not allowed here), paid, expired, cancelled
    static GatewayStatus statusOf(JSONObject link) {
        String paymentId = null;
        JSONArray payments = link.optJSONArray("payments");
        if (payments != null) {
            for (int i = 0; i < payments.length(); i++) {
                JSONObject payment = payments.getJSONObject(i);
                if ("captured".equals(payment.optString("status"))) {
                    paymentId = payment.optString("payment_id", null);
                }
            }
        }
        long amountPaid = link.optLong("amount_paid", 0);
        return switch (link.optString("status")) {
            case "paid" -> new GatewayStatus(GatewayOutcome.PAID, paymentId, amountPaid);
            case "expired" -> new GatewayStatus(GatewayOutcome.EXPIRED, null, null);
            case "cancelled" -> new GatewayStatus(GatewayOutcome.FAILED, null, null);
            default -> new GatewayStatus(GatewayOutcome.PENDING, null, null);
        };
    }

    @Override
    public Optional<GatewayEvent> parseWebhook(String payload, HttpHeaders headers) {
        String signature = headers.getFirst("X-Razorpay-Signature");
        try {
            if (webhookSecret.isBlank() || signature == null || !Utils.verifyWebhookSignature(payload, signature, webhookSecret)) {
                throw new InvalidSignatureException("Razorpay webhook signature does not match");
            }
        } catch (RazorpayException e) {
            throw new InvalidSignatureException("Razorpay webhook signature could not be checked");
        }
        JsonNode event = readJson(payload);
        String type = event.path("event").asText();
        GatewayOutcome outcome = switch (type) {
            case "payment_link.paid" -> GatewayOutcome.PAID;
            case "payment_link.expired" -> GatewayOutcome.EXPIRED;
            case "payment_link.cancelled" -> GatewayOutcome.FAILED;
            // payment.failed is one failed attempt: the link stays open and the customer can try again
            default -> null;
        };
        if (outcome == null) {
            return Optional.empty();
        }
        JsonNode link = event.path("payload").path("payment_link").path("entity");
        JsonNode payment = event.path("payload").path("payment").path("entity");
        String eventId = headers.getFirst("X-Razorpay-Event-Id");
        if (eventId == null) {
            // Older deliveries without the header: the link and its new status identify the event
            eventId = link.path("id").asText() + ":" + type;
        }
        return Optional.of(new GatewayEvent(eventId, type, uuidOrNull(link.path("reference_id").asText()), outcome,
                payment.isMissingNode() ? null : payment.path("id").asText(null),
                payment.isMissingNode() ? null : payment.path("amount").asLong()));
    }

    @Override
    public String referenceFromCallback(Map<String, String> parameters) {
        JSONObject attributes = new JSONObject();
        attributes.put("payment_link_id", parameters.getOrDefault("razorpay_payment_link_id", ""));
        attributes.put("payment_link_reference_id", parameters.getOrDefault("razorpay_payment_link_reference_id", ""));
        attributes.put("payment_link_status", parameters.getOrDefault("razorpay_payment_link_status", ""));
        attributes.put("razorpay_payment_id", parameters.getOrDefault("razorpay_payment_id", ""));
        attributes.put("razorpay_signature", parameters.getOrDefault("razorpay_signature", ""));
        try {
            if (!Utils.verifyPaymentLink(attributes, keySecret)) {
                throw new InvalidSignatureException("Razorpay callback signature does not match");
            }
        } catch (RazorpayException e) {
            throw new InvalidSignatureException("Razorpay callback signature could not be checked");
        }
        return attributes.getString("payment_link_id");
    }

    private JsonNode readJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new InvalidSignatureException("Razorpay webhook is not JSON");
        }
    }

    static UUID uuidOrNull(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
