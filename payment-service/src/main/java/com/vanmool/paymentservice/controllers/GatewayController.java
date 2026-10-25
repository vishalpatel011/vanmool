package com.vanmool.paymentservice.controllers;

import com.vanmool.paymentservice.dtos.PaymentResponseDto;
import com.vanmool.paymentservice.models.GatewayName;
import com.vanmool.paymentservice.services.PaymentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

// What the gateways send back. Neither needs a JWT: a webhook is checked by its signature, a callback by asking the
// gateway. The path names the gateway: /payments/webhooks/razorpay, /payments/callback/stripe, ...
@RestController
public class GatewayController {
    private final PaymentService paymentService;

    public GatewayController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // The raw bytes: the signature is computed over the exact body, so it must not be re-encoded or re-formatted.
    // 200 tells the gateway to stop retrying; anything else makes it send the event again later
    @PostMapping("/payments/webhooks/{gateway}")
    public ResponseEntity<Void> webhook(@PathVariable("gateway") String gateway, @RequestBody byte[] body,
                                        @RequestHeader HttpHeaders headers) {
        paymentService.handleWebhook(gatewayName(gateway), new String(body, StandardCharsets.UTF_8), headers);
        return ResponseEntity.ok().build();
    }

    // Where the customer's browser lands after paying (Razorpay's callback_url, Stripe's success_url)
    @GetMapping("/payments/callback/{gateway}")
    public PaymentResponseDto callback(@PathVariable("gateway") String gateway, @RequestParam Map<String, String> parameters) {
        return paymentService.handleCallback(gatewayName(gateway), parameters);
    }

    private static GatewayName gatewayName(String path) {
        return GatewayName.valueOf(path.toUpperCase(Locale.ROOT));
    }
}
