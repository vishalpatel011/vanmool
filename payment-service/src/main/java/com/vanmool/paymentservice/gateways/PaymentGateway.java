package com.vanmool.paymentservice.gateways;

import com.vanmool.paymentservice.models.GatewayName;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.Optional;

// One payment gateway (the strategy pattern): the rest of the service talks only to this interface, so Razorpay's
// and Stripe's APIs, payloads and signatures stay inside their own classes, and adding a gateway touches nothing else.
public interface PaymentGateway {
    GatewayName name();

    // Configured with keys (or, for the fake gateway, switched on)
    boolean isEnabled();

    // A link where the customer pays this amount. Must be idempotent for the same payment id at the gateway:
    // a retried request must never create a second chargeable link. Throws GatewayUnavailableException
    GatewayLink createLink(GatewayPaymentRequest request);

    // What the gateway says about a link now: the source of truth for callbacks and reconciliation
    GatewayStatus fetchStatus(String gatewayReference);

    // Verifies the webhook's signature (InvalidSignatureException if wrong) and translates it. Empty for an event this
    // service does not act on, which is still acknowledged so the gateway stops sending it
    Optional<GatewayEvent> parseWebhook(String payload, HttpHeaders headers);

    // Closes a link that must no longer be paid (its order was cancelled). Fails if the gateway cannot close it,
    // for example because it has just been paid
    void cancelLink(String gatewayReference);

    // The gateway reference named by the browser redirect after paying. Its query parameters can be forged, so the
    // caller never trusts them: it asks the gateway with fetchStatus
    String referenceFromCallback(Map<String, String> parameters);
}
