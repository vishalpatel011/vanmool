package com.vanmool.orderservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.orderservice.services.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// payment-events as payment-service publishes them
@ExtendWith(MockitoExtension.class)
class PaymentEventsListenerTest {
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID PAYMENT_ID = UUID.fromString("3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10");

    @Mock
    private OrderService orderService;

    private PaymentEventsListener listener() {
        return new PaymentEventsListener(new ObjectMapper(), orderService);
    }

    @Test
    void paymentSucceeded_confirmsTheOrder() throws Exception {
        listener().onPaymentEvent("""
                { "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "PaymentSucceeded",
                  "paymentId": "3b1f9a0e-5c2d-4e8f-9a7b-6c5d4e3f2a10", "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                  "amount": 999.98, "gateway": "RAZORPAY", "gatewayPaymentId": "pay_Test456" }""");

        verify(orderService).confirmPayment(ORDER_ID, PAYMENT_ID);
    }

    @Test
    void paymentFailed_andOtherEvents_leaveTheOrderAlone() throws Exception {
        listener().onPaymentEvent("{ \"eventType\": \"PaymentFailed\", \"orderId\": \"" + ORDER_ID + "\", \"reason\": \"EXPIRED\" }");
        listener().onPaymentEvent("{ \"eventType\": \"RefundCompleted\" }");

        verifyNoInteractions(orderService);
    }

    @Test
    void unreadableOrIncompleteEvents_areRefused() {
        assertThrows(JsonProcessingException.class, () -> listener().onPaymentEvent("not json"));
        assertThrows(MalformedEventException.class,
                () -> listener().onPaymentEvent("{ \"eventType\": \"PaymentSucceeded\", \"orderId\": \"x\" }"));
        verifyNoInteractions(orderService);
    }
}
