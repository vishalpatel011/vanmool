package com.vanmool.paymentservice.controllers;

import com.vanmool.paymentservice.gateways.FakeGateway;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

// The fake gateway's checkout page (only when payment.fake.enabled). Opening the payment link shows two links;
// following one marks the payment paid or failed, sends the signed webhook, and returns the browser to the callback
// URL, like a real gateway's checkout
@RestController
@ConditionalOnProperty(name = "payment.fake.enabled", havingValue = "true")
// A development tool, not part of the API: left out of the OpenAPI description
@Hidden
public class FakeGatewayController {
    private final FakeGateway fakeGateway;

    public FakeGatewayController(FakeGateway fakeGateway) {
        this.fakeGateway = fakeGateway;
    }

    @GetMapping(value = "/fake-gateway/checkout/{reference}", produces = MediaType.TEXT_HTML_VALUE)
    public String checkout(@PathVariable("reference") String reference) {
        return """
                <html><body style="font-family: sans-serif">
                <h2>Vanmool test checkout (fake gateway)</h2>
                <p>No money moves here. Choose what the payment should do:</p>
                <p><a href="/fake-gateway/checkout/%1$s/complete?result=success">Pay</a></p>
                <p><a href="/fake-gateway/checkout/%1$s/complete?result=failure">Fail the payment</a></p>
                </body></html>""".formatted(reference);
    }

    @GetMapping("/fake-gateway/checkout/{reference}/complete")
    public ResponseEntity<Void> complete(@PathVariable("reference") String reference, @RequestParam("result") String result) {
        if (fakeGateway.complete(reference, "success".equals(result)).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/payments/callback/fake?reference=" + reference));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}
