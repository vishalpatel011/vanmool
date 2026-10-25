package com.vanmool.paymentservice.services;

import com.vanmool.paymentservice.clients.OrderClient;
import com.vanmool.paymentservice.events.OutboxWriter;
import com.vanmool.paymentservice.events.PaymentFailedEvent;
import com.vanmool.paymentservice.events.PaymentSucceededEvent;
import com.vanmool.paymentservice.exceptions.OrderNotPayableException;
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
import com.vanmool.paymentservice.models.ProcessedGatewayEvent;
import com.vanmool.paymentservice.repositories.PaymentRepository;
import com.vanmool.paymentservice.repositories.PaymentStatusChangeRepository;
import com.vanmool.paymentservice.repositories.ProcessedGatewayEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Unit test of the payment rules: repositories, order-service, the gateways and the outbox are mocks, so each way a
// payment can go (and go wrong) can be staged
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("a8c1e6ff-556d-4dd2-a238-148b3fca351c");
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final Customer CUSTOMER = new Customer(USER_ID, "asha@example.com", "eyJ.customer.token");

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentStatusChangeRepository statusChangeRepository;
    @Mock
    private ProcessedGatewayEventRepository processedEventRepository;
    @Mock
    private PaymentGatewaySelector gatewaySelector;
    @Mock
    private OrderClient orderClient;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private PaymentGateway razorpay;
    @Mock
    private PaymentGateway stripe;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, statusChangeRepository, processedEventRepository,
                gatewaySelector, orderClient, outboxWriter, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30));
        lenient().when(razorpay.name()).thenReturn(GatewayName.RAZORPAY);
        lenient().when(stripe.name()).thenReturn(GatewayName.STRIPE);
    }

    private void orderIs(String status) {
        when(orderClient.getOrder(ORDER_ID, "eyJ.customer.token"))
                .thenReturn(new OrderClient.Order(ORDER_ID, status, new BigDecimal("999.98")));
    }

    private static Payment payment(PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(ORDER_ID);
        payment.setUserId(USER_ID);
        payment.setAmount(new BigDecimal("999.98"));
        payment.setCurrency("INR");
        payment.setStatus(status);
        payment.setGateway(GatewayName.RAZORPAY);
        payment.setGatewayReference("plink_Test123");
        payment.setPaymentUrl("https://rzp.io/i/Test");
        payment.setCustomerEmail("asha@example.com");
        payment.setExpiresAt(NOW.plus(Duration.ofMinutes(20)));
        return payment;
    }

    private void stored(Payment payment) {
        lenient().when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    @Nested
    @DisplayName("startPayment")
    class Start {
        @Test
        void newPayment_takesTheAmountFromOrderService_andReturnsTheGatewaysLink() {
            // Arrange
            orderIs("PENDING_PAYMENT");
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.empty());
            when(gatewaySelector.candidates(null)).thenReturn(List.of(razorpay));
            when(razorpay.createLink(any())).thenReturn(new GatewayLink("plink_New", "https://rzp.io/i/New"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
            ArgumentCaptor<GatewayPaymentRequest> request = ArgumentCaptor.forClass(GatewayPaymentRequest.class);

            // Act
            PaymentService.StartedPayment started = paymentService.startPayment(CUSTOMER, ORDER_ID, null);

            // Assert: 999.98 rupees = 99998 paise, the payment's own id as the gateway reference, 30 minutes to pay
            verify(razorpay).createLink(request.capture());
            assertEquals(99998, request.getValue().amountInMinorUnits());
            assertEquals(started.payment().id(), request.getValue().paymentId());
            assertEquals(NOW.plus(Duration.ofMinutes(30)), request.getValue().expiresAt());
            assertTrue(started.created());
            assertEquals("https://rzp.io/i/New", started.payment().paymentUrl());
            assertEquals(GatewayName.RAZORPAY, started.payment().gateway());
            assertEquals(PaymentStatus.CREATED, started.payment().status());
        }

        @Test
        void anOpenLinkForTheOrder_isReturnedAgain_andNoSecondLinkIsCreated() {
            // Arrange: a double click, or the customer coming back to pay
            orderIs("PENDING_PAYMENT");
            Payment open = payment(PaymentStatus.CREATED);
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.of(open));

            // Act
            PaymentService.StartedPayment started = paymentService.startPayment(CUSTOMER, ORDER_ID, null);

            // Assert
            assertFalse(started.created());
            assertEquals(open.getId(), started.payment().id());
            verifyNoInteractions(gatewaySelector);
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        void aLinkBeingCreatedByAnotherRequest_isWaitedFor_andReturned() {
            // Arrange: the first request has saved the payment but not its link yet; it appears on the next read
            orderIs("PENDING_PAYMENT");
            Payment beingCreated = payment(PaymentStatus.CREATED);
            beingCreated.setPaymentUrl(null);
            beingCreated.setCreatedAt(NOW.minusSeconds(1));
            Payment withLink = payment(PaymentStatus.CREATED);
            withLink.setId(beingCreated.getId());
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.of(beingCreated));
            when(paymentRepository.findById(beingCreated.getId())).thenReturn(Optional.of(withLink));

            // Act
            PaymentService.StartedPayment started = paymentService.startPayment(CUSTOMER, ORDER_ID, null);

            // Assert: the same payment and link, nothing new created
            assertFalse(started.created());
            assertEquals("https://rzp.io/i/Test", started.payment().paymentUrl());
            verifyNoInteractions(gatewaySelector);
        }

        @Test
        void aPaymentLeftWithoutALinkLongAgo_isClosed_andANewOneStarts() {
            // Arrange: created 10 minutes ago, never got a link (its request failed half way)
            orderIs("PENDING_PAYMENT");
            Payment abandoned = payment(PaymentStatus.CREATED);
            abandoned.setPaymentUrl(null);
            abandoned.setGateway(null);
            abandoned.setCreatedAt(NOW.minus(Duration.ofMinutes(10)));
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.of(abandoned));
            when(paymentRepository.finish(abandoned.getId(), PaymentStatus.FAILED, null)).thenReturn(1);
            when(paymentRepository.existsByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(false);
            when(gatewaySelector.candidates(null)).thenReturn(List.of(razorpay));
            when(razorpay.createLink(any())).thenReturn(new GatewayLink("plink_New", "https://rzp.io/i/New"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            PaymentService.StartedPayment started = paymentService.startPayment(CUSTOMER, ORDER_ID, null);

            // Assert
            verify(paymentRepository).finish(abandoned.getId(), PaymentStatus.FAILED, null);
            assertTrue(started.created());
            assertEquals("https://rzp.io/i/New", started.payment().paymentUrl());
        }

        @Test
        void orderThatIsNotWaitingForPayment_cannotBePaid() {
            orderIs("CONFIRMED");

            OrderNotPayableException exception = assertThrows(OrderNotPayableException.class,
                    () -> paymentService.startPayment(CUSTOMER, ORDER_ID, null));
            assertEquals("Order " + ORDER_ID + " is CONFIRMED and cannot be paid", exception.getMessage());
            verifyNoInteractions(gatewaySelector);
        }

        @Test
        void firstGatewayDown_theNextOneCreatesTheLink() {
            // Arrange
            orderIs("PENDING_PAYMENT");
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.empty());
            when(gatewaySelector.candidates(null)).thenReturn(List.of(razorpay, stripe));
            when(razorpay.createLink(any())).thenThrow(new GatewayUnavailableException("Razorpay is down", null));
            when(stripe.createLink(any())).thenReturn(new GatewayLink("cs_test_New", "https://checkout.stripe.com/c/pay/cs_test_New"));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            PaymentService.StartedPayment started = paymentService.startPayment(CUSTOMER, ORDER_ID, null);

            // Assert
            assertEquals(GatewayName.STRIPE, started.payment().gateway());
        }

        @Test
        void everyGatewayDown_closesTheAttempt_withoutTellingAnyone_andReports503() {
            // Arrange
            orderIs("PENDING_PAYMENT");
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.empty());
            when(gatewaySelector.candidates(null)).thenReturn(List.of(razorpay));
            when(razorpay.createLink(any())).thenThrow(new GatewayUnavailableException("Razorpay is down", null));
            when(paymentRepository.finish(any(), eq(PaymentStatus.FAILED), isNull())).thenReturn(1);

            // Act & Assert: FAILED frees the order for a new attempt; no PaymentFailed, nobody was given a link
            assertThrows(GatewayUnavailableException.class, () -> paymentService.startPayment(CUSTOMER, ORDER_ID, null));
            verify(paymentRepository).finish(any(), eq(PaymentStatus.FAILED), isNull());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void twoStartsAtTheSameMoment_theSecondGetsTheFirstsPayment() {
            // Arrange: the unique index on open payments refuses the second insert
            orderIs("PENDING_PAYMENT");
            Payment first = payment(PaymentStatus.CREATED);
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED))
                    .thenReturn(Optional.empty(), Optional.of(first));
            when(paymentRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_payments_one_open_per_order"));

            // Act
            PaymentService.StartedPayment started = paymentService.startPayment(CUSTOMER, ORDER_ID, null);

            // Assert
            assertEquals(first.getId(), started.payment().id());
            verifyNoInteractions(gatewaySelector);
        }
    }

    @Nested
    @DisplayName("webhooks")
    class Webhooks {
        private GatewayEvent event(Payment payment, GatewayOutcome outcome, long amount) {
            return new GatewayEvent("evt_1", "payment_link.paid", payment.getId(), outcome, "pay_Test456", amount);
        }

        @Test
        void paid_finishesThePaymentOnce_recordsIt_andPublishesPaymentSucceeded() {
            // Arrange
            Payment payment = payment(PaymentStatus.CREATED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.of(event(payment, GatewayOutcome.PAID, 99998)));
            when(paymentRepository.finish(payment.getId(), PaymentStatus.PAID, "pay_Test456")).thenReturn(1);
            ArgumentCaptor<PaymentSucceededEvent> published = ArgumentCaptor.forClass(PaymentSucceededEvent.class);
            ArgumentCaptor<PaymentStatusChange> recorded = ArgumentCaptor.forClass(PaymentStatusChange.class);

            // Act
            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            // Assert
            verify(processedEventRepository).saveAndFlush(any(ProcessedGatewayEvent.class));
            verify(statusChangeRepository).save(recorded.capture());
            assertEquals(PaymentStatus.PAID, recorded.getValue().getStatus());
            assertEquals(PaymentStatusChange.Source.WEBHOOK, recorded.getValue().getSource());
            verify(outboxWriter).add(eq("payment-events"), eq(ORDER_ID.toString()), published.capture());
            assertEquals(ORDER_ID, published.getValue().orderId());
            assertEquals(new BigDecimal("999.98"), published.getValue().amount());
            assertEquals("pay_Test456", published.getValue().gatewayPaymentId());
        }

        @Test
        void sameEventDeliveredAgain_changesNothing() {
            // Arrange: the processed-event row already exists
            Payment payment = payment(PaymentStatus.CREATED);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.of(event(payment, GatewayOutcome.PAID, 99998)));
            when(processedEventRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("PRIMARY"));

            // Act
            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            // Assert
            verify(paymentRepository, never()).finish(any(), any(), any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void paidWithTheWrongAmount_isNotAccepted_andIsRecordedForReview() {
            // Arrange: 1 paisa paid for a 999.98 order
            Payment payment = payment(PaymentStatus.CREATED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.of(event(payment, GatewayOutcome.PAID, 1)));
            ArgumentCaptor<PaymentStatusChange> recorded = ArgumentCaptor.forClass(PaymentStatusChange.class);

            // Act
            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            // Assert
            verify(paymentRepository, never()).finish(any(), any(), any());
            verify(statusChangeRepository).save(recorded.capture());
            assertTrue(recorded.getValue().getDetail().startsWith("MISMATCH: paid 1 minor units, due 99998"));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void expired_publishesPaymentFailed_withTheReason() {
            // Arrange
            Payment payment = payment(PaymentStatus.CREATED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.of(event(payment, GatewayOutcome.EXPIRED, 0)));
            when(paymentRepository.finish(payment.getId(), PaymentStatus.EXPIRED, "pay_Test456")).thenReturn(1);
            ArgumentCaptor<PaymentFailedEvent> published = ArgumentCaptor.forClass(PaymentFailedEvent.class);

            // Act
            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            // Assert
            verify(outboxWriter).add(eq("payment-events"), eq(ORDER_ID.toString()), published.capture());
            assertEquals("EXPIRED", published.getValue().reason());
        }

        @Test
        void aPaymentAnotherPathAlreadyFinished_isNotFinishedOrPublishedTwice() {
            // Arrange: the callback marked it PAID between our read and our UPDATE
            Payment payment = payment(PaymentStatus.CREATED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.of(event(payment, GatewayOutcome.PAID, 99998)));
            when(paymentRepository.finish(payment.getId(), PaymentStatus.PAID, "pay_Test456")).thenReturn(0);

            // Act
            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            // Assert
            verify(statusChangeRepository, never()).save(any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void eventForAPaymentThatIsNotOurs_isAcknowledgedAndIgnored() {
            // Arrange
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.of(
                    new GatewayEvent("evt_1", "payment_link.paid", null, GatewayOutcome.PAID, "pay_x", 100L)));

            // Act
            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            // Assert
            verify(paymentRepository, never()).finish(any(), any(), any());
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void eventTypeWeDoNotActOn_storesNothing() {
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.parseWebhook(anyString(), any())).thenReturn(Optional.empty());

            paymentService.handleWebhook(GatewayName.RAZORPAY, "{}", new HttpHeaders());

            verifyNoInteractions(processedEventRepository, outboxWriter);
        }
    }

    @Nested
    @DisplayName("callback and reconciliation")
    class Reconcile {
        @Test
        void callback_asksTheGateway_andAppliesWhatItSays() {
            // Arrange
            Payment payment = payment(PaymentStatus.CREATED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.referenceFromCallback(any())).thenReturn("plink_Test123");
            when(paymentRepository.findByGatewayAndGatewayReference(GatewayName.RAZORPAY, "plink_Test123"))
                    .thenReturn(Optional.of(payment));
            when(razorpay.fetchStatus("plink_Test123")).thenReturn(new GatewayStatus(GatewayOutcome.PAID, "pay_Test456", 99998L));
            when(paymentRepository.finish(payment.getId(), PaymentStatus.PAID, "pay_Test456")).thenReturn(1);

            // Act
            paymentService.handleCallback(GatewayName.RAZORPAY, Map.of("razorpay_payment_link_status", "paid"));

            // Assert
            verify(outboxWriter).add(eq("payment-events"), eq(ORDER_ID.toString()), any(PaymentSucceededEvent.class));
        }

        @Test
        void callbackForAnUnknownLink_isNotFound() {
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.referenceFromCallback(any())).thenReturn("plink_Unknown");
            when(paymentRepository.findByGatewayAndGatewayReference(GatewayName.RAZORPAY, "plink_Unknown")).thenReturn(Optional.empty());

            assertThrows(PaymentNotFoundException.class, () -> paymentService.handleCallback(GatewayName.RAZORPAY, Map.of()));
        }

        @Test
        void paymentWeRecordedAsExpired_butTheGatewaySaysPaid_isAMismatchForManualReview() {
            // Arrange: money was taken after we closed the payment (the lecture's "manual intervention" case)
            Payment payment = payment(PaymentStatus.EXPIRED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.fetchStatus("plink_Test123")).thenReturn(new GatewayStatus(GatewayOutcome.PAID, "pay_Late", 99998L));
            ArgumentCaptor<PaymentStatusChange> recorded = ArgumentCaptor.forClass(PaymentStatusChange.class);

            // Act
            PaymentService.ApplyResult result = paymentService.reconcile(payment, PaymentStatusChange.Source.RECONCILIATION);

            // Assert: the status stays EXPIRED, the history says why someone must look at it
            assertSame(PaymentService.ApplyResult.MISMATCH, result);
            verify(paymentRepository, never()).finish(any(), any(), any());
            verify(statusChangeRepository).save(recorded.capture());
            assertEquals(PaymentStatus.EXPIRED, recorded.getValue().getStatus());
            assertTrue(recorded.getValue().getDetail().startsWith("MISMATCH: gateway says PAID"));
        }

        @Test
        void gatewayStillPending_changesNothing() {
            Payment payment = payment(PaymentStatus.CREATED);
            stored(payment);
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(razorpay.fetchStatus("plink_Test123")).thenReturn(new GatewayStatus(GatewayOutcome.PENDING, null, null));

            assertSame(PaymentService.ApplyResult.UNCHANGED, paymentService.reconcile(payment, PaymentStatusChange.Source.RECONCILIATION));
            verify(paymentRepository, never()).finish(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("closeOpenPayment (order cancelled)")
    class Close {
        @Test
        void openLink_isClosedAtTheGateway_andThePaymentExpires_withoutAnEvent() {
            Payment open = payment(PaymentStatus.CREATED);
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.of(open));
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            when(paymentRepository.finish(open.getId(), PaymentStatus.EXPIRED, null)).thenReturn(1);

            paymentService.closeOpenPayment(ORDER_ID);

            verify(razorpay).cancelLink("plink_Test123");
            verify(paymentRepository).finish(open.getId(), PaymentStatus.EXPIRED, null);
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void linkPaidJustBeforeTheCancel_isRecordedAsPaid_soTheRefundCanBeSeen() {
            // Arrange: Razorpay refuses to cancel a paid link; asking it shows the payment
            Payment open = payment(PaymentStatus.CREATED);
            stored(open);
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.of(open));
            when(gatewaySelector.require(GatewayName.RAZORPAY)).thenReturn(razorpay);
            org.mockito.Mockito.doThrow(new GatewayUnavailableException("already paid", null)).when(razorpay).cancelLink("plink_Test123");
            when(razorpay.fetchStatus("plink_Test123")).thenReturn(new GatewayStatus(GatewayOutcome.PAID, "pay_Test456", 99998L));
            when(paymentRepository.finish(open.getId(), PaymentStatus.PAID, "pay_Test456")).thenReturn(1);

            // Act
            paymentService.closeOpenPayment(ORDER_ID);

            // Assert: PaymentSucceeded goes out; order-service finds the order cancelled and flags the refund
            verify(outboxWriter).add(eq("payment-events"), eq(ORDER_ID.toString()), any(PaymentSucceededEvent.class));
            verify(paymentRepository, never()).finish(open.getId(), PaymentStatus.EXPIRED, null);
        }

        @Test
        void noOpenPayment_doesNothing() {
            when(paymentRepository.findByOrderIdAndStatus(ORDER_ID, PaymentStatus.CREATED)).thenReturn(Optional.empty());

            paymentService.closeOpenPayment(ORDER_ID);

            verifyNoInteractions(gatewaySelector, outboxWriter);
        }
    }

    @Test
    void getPayment_ofAnotherCustomer_isNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdAndUserId(paymentId, USER_ID)).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class, () -> paymentService.getPayment(USER_ID, paymentId));
    }
}
