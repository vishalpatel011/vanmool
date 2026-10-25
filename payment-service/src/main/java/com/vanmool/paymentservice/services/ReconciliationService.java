package com.vanmool.paymentservice.services;

import com.vanmool.paymentservice.dtos.ReconciliationReportDto;
import com.vanmool.paymentservice.gateways.GatewayNotEnabledException;
import com.vanmool.paymentservice.gateways.GatewayUnavailableException;
import com.vanmool.paymentservice.models.Payment;
import com.vanmool.paymentservice.models.PaymentStatus;
import com.vanmool.paymentservice.models.PaymentStatusChange.Source;
import com.vanmool.paymentservice.repositories.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Payment reconciliation: compares this service's records with the gateways', which are the source of truth.
//   - open payments older than 2 minutes: their webhook may have been lost (or the customer closed the browser);
//     the gateway says whether they were paid, failed or expired;
//   - payments finished in the last day: if the gateway disagrees (money taken for a payment recorded as expired),
//     the mismatch is recorded and logged for manual review.
@Slf4j
@Service
public class ReconciliationService {
    private static final Duration WEBHOOK_GRACE = Duration.ofMinutes(2);
    private static final Duration LOOK_BACK = Duration.ofDays(1);

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final Clock clock;

    public ReconciliationService(PaymentRepository paymentRepository, PaymentService paymentService, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation-interval}", initialDelayString = "PT1M")
    public ReconciliationReportDto reconcile() {
        Instant now = clock.instant();
        List<Payment> payments = new ArrayList<>(paymentRepository.findByStatusAndCreatedAtBetween(PaymentStatus.CREATED,
                now.minus(LOOK_BACK), now.minus(WEBHOOK_GRACE)));
        for (PaymentStatus finished : List.of(PaymentStatus.PAID, PaymentStatus.FAILED, PaymentStatus.EXPIRED)) {
            payments.addAll(paymentRepository.findByStatusAndCreatedAtBetween(finished, now.minus(LOOK_BACK), now));
        }
        int checked = 0;
        int changed = 0;
        int mismatches = 0;
        int errors = 0;
        for (Payment payment : payments) {
            if (payment.getGateway() == null || payment.getGatewayReference() == null) {
                continue;
            }
            try {
                checked++;
                switch (paymentService.reconcile(payment, Source.RECONCILIATION)) {
                    case CHANGED -> changed++;
                    case MISMATCH -> mismatches++;
                    case UNCHANGED -> { }
                }
            } catch (GatewayUnavailableException | GatewayNotEnabledException e) {
                errors++;
                log.warn("Could not reconcile payment {} with {}: {}", payment.getId(), payment.getGateway(), e.getMessage());
            }
        }
        ReconciliationReportDto report = new ReconciliationReportDto(checked, changed, mismatches, errors);
        if (checked > 0) {
            log.info("Reconciliation: {}", report);
        }
        return report;
    }
}
