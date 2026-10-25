package com.vanmool.paymentservice.services;

import com.vanmool.paymentservice.clients.OrderClient;
import com.vanmool.paymentservice.configs.KafkaConfig;
import com.vanmool.paymentservice.dtos.PaymentDetailsDto;
import com.vanmool.paymentservice.dtos.PaymentResponseDto;
import com.vanmool.paymentservice.events.OutboxWriter;
import com.vanmool.paymentservice.events.PaymentFailedEvent;
import com.vanmool.paymentservice.events.PaymentSucceededEvent;
import com.vanmool.paymentservice.exceptions.OrderNotPayableException;
import com.vanmool.paymentservice.exceptions.PaymentInProgressException;
import com.vanmool.paymentservice.exceptions.PaymentNotFoundException;
import com.vanmool.paymentservice.gateways.GatewayEvent;
import com.vanmool.paymentservice.gateways.GatewayLink;
import com.vanmool.paymentservice.gateways.GatewayOutcome;
import com.vanmool.paymentservice.gateways.GatewayPaymentRequest;
import com.vanmool.paymentservice.gateways.GatewayStatus;
import com.vanmool.paymentservice.gateways.GatewayUnavailableException;
import com.vanmool.paymentservice.gateways.PaymentGateway;
import com.vanmool.paymentservice.gateways.PaymentGatewaySelector;
import com.vanmool.paymentservice.models.GatewayName;
import com.vanmool.paymentservice.models.Payment;
import com.vanmool.paymentservice.models.PaymentStatus;
import com.vanmool.paymentservice.models.PaymentStatusChange;
import com.vanmool.paymentservice.models.PaymentStatusChange.Source;
import com.vanmool.paymentservice.models.ProcessedGatewayEvent;
import com.vanmool.paymentservice.repositories.PaymentRepository;
import com.vanmool.paymentservice.repositories.PaymentStatusChangeRepository;
import com.vanmool.paymentservice.repositories.ProcessedGatewayEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// The payment flow:
//   1. the customer asks to pay an order; its amount comes from order-service, never from the client;
//   2. a gateway creates a payment link (another gateway if the first fails); the customer pays on the gateway's page;
//   3. the result arrives in up to three ways: the gateway's webhook (server to server, the reliable one), the
//      browser's return to the callback URL (fast, but the customer may close the tab), and the reconciliation that
//      asks the gateway itself. All three end in apply(), which acts only once per payment.
// A finished payment publishes PaymentSucceeded or PaymentFailed through the outbox, in the same transaction.
@Slf4j
@Service
public class PaymentService {
    public enum ApplyResult { UNCHANGED, CHANGED, MISMATCH }

    public record StartedPayment(PaymentResponseDto payment, boolean created) {
    }

    // A gateway answers within this time (Razorpay's SDK waits up to 60 s, Stripe's calls are capped lower), so a
    // payment that still has no link after it was abandoned half way
    static final Duration LINK_CREATION_LIMIT = Duration.ofMinutes(5);
    // How long a second request for the same order waits for the first one's link: 30 x 100 ms
    private static final int LINK_WAIT_ATTEMPTS = 30;
    private static final Duration LINK_WAIT_STEP = Duration.ofMillis(100);

    private final PaymentRepository paymentRepository;
    private final PaymentStatusChangeRepository statusChangeRepository;
    private final ProcessedGatewayEventRepository processedEventRepository;
    private final PaymentGatewaySelector gatewaySelector;
    private final OrderClient orderClient;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration linkValidity;

    public PaymentService(PaymentRepository paymentRepository, PaymentStatusChangeRepository statusChangeRepository,
                          ProcessedGatewayEventRepository processedEventRepository, PaymentGatewaySelector gatewaySelector,
                          OrderClient orderClient, OutboxWriter outboxWriter, TransactionTemplate transactionTemplate,
                          Clock clock, @Value("${payment.link-validity}") Duration linkValidity) {
        this.paymentRepository = paymentRepository;
        this.statusChangeRepository = statusChangeRepository;
        this.processedEventRepository = processedEventRepository;
        this.gatewaySelector = gatewaySelector;
        this.orderClient = orderClient;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.linkValidity = linkValidity;
    }

    public StartedPayment startPayment(Customer customer, UUID orderId, GatewayName requestedGateway) {
        OrderClient.Order order = orderClient.getOrder(orderId, customer.bearerToken());
        if (!"PENDING_PAYMENT".equals(order.status())) {
            throw new OrderNotPayableException(orderId, order.status());
        }

        // One open payment per order. Asking again (a double click, a retry) returns the same link, so the customer
        // can never be given two links to pay the same order
        Optional<Payment> open = paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.CREATED);
        if (open.isPresent()) {
            Payment payment = open.get();
            if (payment.getPaymentUrl() == null && payment.getCreatedAt().isAfter(clock.instant().minus(LINK_CREATION_LIMIT))) {
                // A request a moment ago (a double click) is creating this payment's link right now: wait for it
                payment = waitForLink(payment);
            }
            if (payment.getPaymentUrl() != null && payment.getExpiresAt().isAfter(clock.instant())) {
                return new StartedPayment(PaymentResponseDto.from(payment), false);
            }
            if (payment.getPaymentUrl() == null && payment.getStatus() == PaymentStatus.CREATED) {
                if (payment.getCreatedAt().isAfter(clock.instant().minus(LINK_CREATION_LIMIT))) {
                    throw new PaymentInProgressException("A payment link for order " + orderId
                            + " is being created; please try again in a moment");
                }
                // No link long after it was started: the request that created it failed half way, and nobody was
                // given anything to pay. Close it so the order can be paid
                Payment abandoned = payment;
                transactionTemplate.executeWithoutResult(status -> {
                    if (paymentRepository.finish(abandoned.getId(), PaymentStatus.FAILED, null) == 1) {
                        record(abandoned.getId(), PaymentStatus.FAILED, Source.API, "No payment link was ever created");
                    }
                });
            } else if (payment.getStatus() == PaymentStatus.CREATED) {
                // Past its expiry: ask the gateway, which normally reports EXPIRED and so closes it
                reconcile(payment, Source.API);
            }
            if (paymentRepository.existsByOrderIdAndStatus(orderId, PaymentStatus.CREATED)) {
                throw new PaymentInProgressException("The last payment link for order " + orderId
                        + " has not been closed by the gateway yet; please try again in a minute");
            }
        }

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(orderId);
        payment.setUserId(customer.id());
        payment.setAmount(order.totalAmount());
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setCustomerEmail(customer.email());
        payment.setExpiresAt(clock.instant().plus(linkValidity));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                paymentRepository.saveAndFlush(payment);
                record(payment.getId(), PaymentStatus.CREATED, Source.API, "Order total " + order.totalAmount() + " INR");
            });
        } catch (DataIntegrityViolationException e) {
            // Another request for the same order created the open payment at the same moment (unique index)
            return paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.CREATED)
                    .map(existing -> new StartedPayment(PaymentResponseDto.from(existing), false))
                    .orElseThrow(() -> e);
        }

        GatewayPaymentRequest request = new GatewayPaymentRequest(payment.getId(), orderId,
                payment.amountInMinorUnits(), payment.getCurrency(), customer.email(),
                "Vanmool order " + orderId.toString().substring(0, 8), payment.getExpiresAt());
        GatewayUnavailableException lastFailure = null;
        for (PaymentGateway gateway : gatewaySelector.candidates(requestedGateway)) {
            try {
                GatewayLink link = gateway.createLink(request);
                payment.setGateway(gateway.name());
                payment.setGatewayReference(link.reference());
                payment.setPaymentUrl(link.url());
                Payment saved = transactionTemplate.execute(status -> {
                    Payment updated = paymentRepository.save(payment);
                    record(payment.getId(), PaymentStatus.CREATED, Source.API, "Payment link created at " + gateway.name());
                    return updated;
                });
                return new StartedPayment(PaymentResponseDto.from(saved), true);
            } catch (GatewayUnavailableException e) {
                log.warn("{} could not create a link for payment {}; trying the next gateway: {}", gateway.name(),
                        payment.getId(), e.getMessage());
                lastFailure = e;
            }
        }
        // No gateway could create a link: close this attempt so the order can be paid later. Nothing was offered to
        // the customer, so no PaymentFailed is published
        transactionTemplate.executeWithoutResult(status -> {
            if (paymentRepository.finish(payment.getId(), PaymentStatus.FAILED, null) == 1) {
                record(payment.getId(), PaymentStatus.FAILED, Source.API, "No payment gateway could create a link");
            }
        });
        throw lastFailure != null ? lastFailure
                : new GatewayUnavailableException("No payment gateway is enabled", null);
    }

    // Re-reads the payment a few times, until the request that is creating its link has saved it
    private Payment waitForLink(Payment payment) {
        Payment current = payment;
        for (int attempt = 0; attempt < LINK_WAIT_ATTEMPTS && current.getPaymentUrl() == null
                && current.getStatus() == PaymentStatus.CREATED; attempt++) {
            try {
                Thread.sleep(LINK_WAIT_STEP.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            current = paymentRepository.findById(payment.getId()).orElse(current);
        }
        return current;
    }

    public PaymentDetailsDto getPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return new PaymentDetailsDto(PaymentResponseDto.from(payment),
                statusChangeRepository.findByPaymentIdOrderByRecordedAtAsc(paymentId).stream()
                        .map(PaymentDetailsDto.StatusChange::from).toList());
    }

    // A webhook: verified by the gateway class (signature), then handled at most once per gateway event id. The
    // event id row and the payment change are one transaction: a second delivery finds the row and changes nothing
    public void handleWebhook(GatewayName gatewayName, String payload, HttpHeaders headers) {
        Optional<GatewayEvent> parsed = gatewaySelector.require(gatewayName).parseWebhook(payload, headers);
        if (parsed.isEmpty()) {
            return;
        }
        GatewayEvent event = parsed.get();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                processedEventRepository.saveAndFlush(new ProcessedGatewayEvent(gatewayName + ":" + event.eventId(),
                        event.eventType(), clock.instant()));
                Optional<Payment> payment = event.paymentId() == null ? Optional.empty()
                        : paymentRepository.findById(event.paymentId());
                if (payment.isEmpty()) {
                    // Not one of ours (for example another app on the same gateway account): acknowledged and ignored
                    log.warn("{} event {} is about an unknown payment; ignored", gatewayName, event.eventId());
                    return;
                }
                apply(payment.get(), event.outcome(), event.gatewayPaymentId(), event.amountInMinorUnits(),
                        Source.WEBHOOK, event.eventType());
            });
        } catch (DataIntegrityViolationException e) {
            log.info("{} event {} was already handled; ignored", gatewayName, event.eventId());
        }
    }

    // The browser comes back from the gateway. The URL's parameters only say which link to look at; what happened is
    // asked from the gateway, so a changed URL cannot mark a payment paid
    public PaymentResponseDto handleCallback(GatewayName gatewayName, Map<String, String> parameters) {
        PaymentGateway gateway = gatewaySelector.require(gatewayName);
        String reference = gateway.referenceFromCallback(parameters);
        Payment payment = paymentRepository.findByGatewayAndGatewayReference(gatewayName, reference)
                .orElseThrow(() -> new PaymentNotFoundException("No payment for " + gatewayName + " link " + reference));
        reconcile(payment, Source.CALLBACK);
        return PaymentResponseDto.from(paymentRepository.findById(payment.getId()).orElseThrow());
    }

    // OrderCancelled from order-service: the order's open link is closed at the gateway, so nobody can pay a
    // cancelled order. If the gateway will not close it (it has just been paid), the gateway is asked: a payment
    // that went through is recorded as PAID, and order-service then reports that the cancelled order needs a refund
    public void closeOpenPayment(UUID orderId) {
        Optional<Payment> open = paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.CREATED);
        if (open.isEmpty()) {
            return;
        }
        Payment payment = open.get();
        if (payment.getGateway() != null) {
            try {
                gatewaySelector.require(payment.getGateway()).cancelLink(payment.getGatewayReference());
            } catch (GatewayUnavailableException e) {
                if (reconcile(payment, Source.ORDER_EVENT) == ApplyResult.CHANGED) {
                    return;
                }
                throw e;
            }
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (paymentRepository.finish(payment.getId(), PaymentStatus.EXPIRED, null) == 1) {
                record(payment.getId(), PaymentStatus.EXPIRED, Source.ORDER_EVENT, "Order cancelled: payment link closed");
            }
        });
    }

    // Asks the payment's gateway, the source of truth, and applies what it says
    public ApplyResult reconcile(Payment payment, Source source) {
        GatewayStatus status = gatewaySelector.require(payment.getGateway()).fetchStatus(payment.getGatewayReference());
        return transactionTemplate.execute(tx -> apply(payment, status.outcome(), status.gatewayPaymentId(),
                status.amountInMinorUnits(), source, "Gateway status " + status.outcome()));
    }

    // Must run inside a transaction. Moves the payment out of CREATED at most once (conditional UPDATE), appends the
    // status change and the event. A finished payment is never changed again; if the gateway disagrees with it (money
    // taken for an expired payment), that is recorded and logged for a person to sort out.
    private ApplyResult apply(Payment payment, GatewayOutcome outcome, String gatewayPaymentId, Long amountInMinorUnits,
                              Source source, String detail) {
        PaymentStatus target = switch (outcome) {
            case PAID -> PaymentStatus.PAID;
            case FAILED -> PaymentStatus.FAILED;
            case EXPIRED -> PaymentStatus.EXPIRED;
            case PENDING -> null;
        };
        PaymentStatus current = paymentRepository.findById(payment.getId()).orElseThrow().getStatus();
        if (target == null || target == current) {
            return ApplyResult.UNCHANGED;
        }
        if (current.isFinal()) {
            log.error("Payment {} is {} but {} says {}: needs manual review", payment.getId(), current,
                    payment.getGateway(), outcome);
            record(payment.getId(), current, source, "MISMATCH: gateway says " + outcome + " (" + detail + ")");
            return ApplyResult.MISMATCH;
        }
        if (target == PaymentStatus.PAID && amountInMinorUnits != null && amountInMinorUnits != payment.amountInMinorUnits()) {
            log.error("Payment {} was paid {} but {} was due: needs manual review", payment.getId(), amountInMinorUnits,
                    payment.amountInMinorUnits());
            record(payment.getId(), current, source, "MISMATCH: paid " + amountInMinorUnits + " minor units, due "
                    + payment.amountInMinorUnits());
            return ApplyResult.MISMATCH;
        }
        if (paymentRepository.finish(payment.getId(), target, gatewayPaymentId) == 0) {
            return ApplyResult.UNCHANGED;
        }
        record(payment.getId(), target, source, detail);
        String key = payment.getOrderId().toString();
        if (target == PaymentStatus.PAID) {
            outboxWriter.add(KafkaConfig.PAYMENT_EVENTS_TOPIC, key, PaymentSucceededEvent.of(payment, gatewayPaymentId, clock.instant()));
        } else {
            outboxWriter.add(KafkaConfig.PAYMENT_EVENTS_TOPIC, key, PaymentFailedEvent.of(payment, target, clock.instant()));
        }
        log.info("Payment {} for order {} is {} ({})", payment.getId(), payment.getOrderId(), target, source);
        return ApplyResult.CHANGED;
    }

    private void record(UUID paymentId, PaymentStatus status, Source source, String detail) {
        statusChangeRepository.save(new PaymentStatusChange(paymentId, status, source, detail, Instant.now(clock)));
    }
}
