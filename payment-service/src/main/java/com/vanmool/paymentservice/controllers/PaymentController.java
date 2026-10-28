package com.vanmool.paymentservice.controllers;

import com.vanmool.paymentservice.dtos.PaymentDetailsDto;
import com.vanmool.paymentservice.dtos.PaymentResponseDto;
import com.vanmool.paymentservice.dtos.ReconciliationReportDto;
import com.vanmool.paymentservice.dtos.StartPaymentRequestDto;
import com.vanmool.paymentservice.exceptions.NotAUserTokenException;
import com.vanmool.paymentservice.services.Customer;
import com.vanmool.paymentservice.services.PaymentService;
import com.vanmool.paymentservice.services.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@Tag(name = "Payments", description = "Paying for an order")
public class PaymentController {
    private final PaymentService paymentService;
    private final ReconciliationService reconciliationService;

    public PaymentController(PaymentService paymentService, ReconciliationService reconciliationService) {
        this.paymentService = paymentService;
        this.reconciliationService = reconciliationService;
    }

    // 201 with a new payment link; 200 with the order's open link if there already is one
    @Operation(summary = "Start a payment for an order: returns the gateway's payment page")
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponseDto> startPayment(@AuthenticationPrincipal Jwt jwt,
                                                           @Valid @RequestBody StartPaymentRequestDto request,
                                                           UriComponentsBuilder uriComponentsBuilder) {
        PaymentService.StartedPayment started = paymentService.startPayment(customer(jwt), request.orderId(), request.gateway());
        if (!started.created()) {
            return ResponseEntity.ok(started.payment());
        }
        return ResponseEntity.created(uriComponentsBuilder.path("/payments/{id}").buildAndExpand(started.payment().id()).toUri())
                .body(started.payment());
    }

    @Operation(summary = "A payment and its status history")
    @GetMapping("/payments/{id}")
    public PaymentDetailsDto getPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID paymentId) {
        return paymentService.getPayment(customer(jwt).id(), paymentId);
    }

    // ADMIN only: run the reconciliation now instead of waiting for its schedule
    @Operation(summary = "ADMIN: compare open and recent payments with their gateways")
    @PostMapping("/payments/reconciliation")
    public ResponseEntity<ReconciliationReportDto> reconcile() {
        return new ResponseEntity<>(reconciliationService.reconcile(), HttpStatus.OK);
    }

    private static Customer customer(Jwt jwt) {
        try {
            return new Customer(UUID.fromString(jwt.getSubject() == null ? "" : jwt.getSubject()),
                    jwt.getClaimAsString("email"), jwt.getTokenValue());
        } catch (IllegalArgumentException e) {
            throw new NotAUserTokenException();
        }
    }
}
