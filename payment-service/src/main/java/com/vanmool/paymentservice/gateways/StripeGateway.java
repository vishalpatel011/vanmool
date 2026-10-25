package com.vanmool.paymentservice.gateways;

import com.stripe.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.vanmool.paymentservice.models.GatewayName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

// Stripe Checkout (cards, international), through Stripe's Java SDK.
//   - create: a Checkout Session, a one-time page for this payment's exact amount, built from inline price data (no
//     Stripe Product or Price objects). Sent with our payment id as Stripe's Idempotency-Key: a retried request gets
//     the same session back instead of a second one.
//   - webhook: Stripe-Signature carries a timestamp and an HMAC-SHA256 of "timestamp.body"; Webhook.constructEvent
//     checks both, so an old signed event cannot be replayed later.
//   - callback: the success page gets ?session_id=..., which is then looked up at Stripe.
@Component
public class StripeGateway implements PaymentGateway {
    private final String secretKey;
    private final String webhookSecret;
    private final String publicBaseUrl;

    public StripeGateway(@Value("${payment.stripe.secret-key}") String secretKey,
                         @Value("${payment.stripe.webhook-secret}") String webhookSecret,
                         @Value("${payment.public-base-url}") String publicBaseUrl) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public GatewayName name() {
        return GatewayName.STRIPE;
    }

    @Override
    public boolean isEnabled() {
        return !secretKey.isBlank();
    }

    private StripeClient client() {
        return new StripeClient(secretKey);
    }

    @Override
    public GatewayLink createLink(GatewayPaymentRequest request) {
        String callback = publicBaseUrl + "/payments/callback/stripe?session_id={CHECKOUT_SESSION_ID}";
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setClientReferenceId(request.paymentId().toString())
                .putMetadata("paymentId", request.paymentId().toString())
                .putMetadata("orderId", request.orderId().toString())
                .setExpiresAt(request.expiresAt().getEpochSecond())
                .setSuccessUrl(callback)
                .setCancelUrl(callback)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(request.currency().toLowerCase())
                                .setUnitAmount(request.amountInMinorUnits())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(request.description())
                                        .build())
                                .build())
                        .build());
        if (request.customerEmail() != null) {
            params.setCustomerEmail(request.customerEmail());
        }
        try {
            Session session = client().v1().checkout().sessions().create(params.build(),
                    RequestOptions.builder().setIdempotencyKey(request.paymentId().toString()).build());
            return new GatewayLink(session.getId(), session.getUrl());
        } catch (StripeException | RuntimeException e) {
            throw new GatewayUnavailableException("Stripe could not create the checkout session: " + e.getMessage(), e);
        }
    }

    @Override
    public GatewayStatus fetchStatus(String gatewayReference) {
        try {
            return statusOf(client().v1().checkout().sessions().retrieve(gatewayReference));
        } catch (StripeException | RuntimeException e) {
            throw new GatewayUnavailableException("Stripe could not return the checkout session: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelLink(String gatewayReference) {
        try {
            client().v1().checkout().sessions().expire(gatewayReference);
        } catch (StripeException | RuntimeException e) {
            throw new GatewayUnavailableException("Stripe could not expire the checkout session: " + e.getMessage(), e);
        }
    }

    // A session is open, complete or expired; its payment_status is paid, unpaid or no_payment_required
    static GatewayStatus statusOf(Session session) {
        if ("paid".equals(session.getPaymentStatus())) {
            return new GatewayStatus(GatewayOutcome.PAID, session.getPaymentIntent(), session.getAmountTotal());
        }
        if ("expired".equals(session.getStatus())) {
            return new GatewayStatus(GatewayOutcome.EXPIRED, null, null);
        }
        return new GatewayStatus(GatewayOutcome.PENDING, null, null);
    }

    @Override
    public Optional<GatewayEvent> parseWebhook(String payload, HttpHeaders headers) {
        Event event;
        try {
            if (webhookSecret.isBlank()) {
                throw new InvalidSignatureException("Stripe webhook secret is not configured");
            }
            event = Webhook.constructEvent(payload, headers.getFirst("Stripe-Signature"), webhookSecret);
        } catch (SignatureVerificationException | RuntimeException e) {
            if (e instanceof InvalidSignatureException invalid) {
                throw invalid;
            }
            throw new InvalidSignatureException("Stripe webhook signature does not match");
        }
        if (!event.getType().startsWith("checkout.session.")) {
            return Optional.empty();
        }
        Session session = session(event);
        GatewayOutcome outcome = switch (event.getType()) {
            // Card payments are paid when the session completes; slower methods confirm later (async_payment_*)
            case "checkout.session.completed" -> "paid".equals(session.getPaymentStatus()) ? GatewayOutcome.PAID : null;
            case "checkout.session.async_payment_succeeded" -> GatewayOutcome.PAID;
            case "checkout.session.async_payment_failed" -> GatewayOutcome.FAILED;
            case "checkout.session.expired" -> GatewayOutcome.EXPIRED;
            default -> null;
        };
        if (outcome == null) {
            return Optional.empty();
        }
        return Optional.of(new GatewayEvent(event.getId(), event.getType(),
                RazorpayGateway.uuidOrNull(session.getClientReferenceId()), outcome, session.getPaymentIntent(),
                session.getAmountTotal()));
    }

    private static Session session(Event event) {
        try {
            StripeObject object = event.getDataObjectDeserializer().getObject()
                    .orElse(event.getDataObjectDeserializer().deserializeUnsafe());
            return (Session) object;
        } catch (EventDataObjectDeserializationException | ClassCastException e) {
            throw new InvalidSignatureException("Stripe event does not hold a checkout session");
        }
    }

    @Override
    public String referenceFromCallback(Map<String, String> parameters) {
        String sessionId = parameters.get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            throw new InvalidSignatureException("Stripe callback without a session_id");
        }
        return sessionId;
    }
}
