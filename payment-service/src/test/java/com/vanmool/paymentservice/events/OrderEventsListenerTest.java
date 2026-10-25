package com.vanmool.paymentservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.paymentservice.services.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderEventsListenerTest {
    @Mock
    private PaymentService paymentService;

    private OrderEventsListener listener() {
        return new OrderEventsListener(new ObjectMapper(), paymentService);
    }

    @Test
    void orderCancelled_closesTheOrdersOpenPayment() throws Exception {
        listener().onOrderEvent("{ \"eventType\": \"OrderCancelled\", \"orderId\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\", \"reason\": \"CUSTOMER\" }");

        verify(paymentService).closeOpenPayment(UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"));
    }

    @Test
    void otherEvents_areIgnored_andBrokenOnesRefused() throws Exception {
        listener().onOrderEvent("{ \"eventType\": \"OrderPlaced\", \"orderId\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\" }");
        verifyNoInteractions(paymentService);

        assertThrows(JsonProcessingException.class, () -> listener().onOrderEvent("not json"));
        assertThrows(MalformedEventException.class, () -> listener().onOrderEvent("{ \"eventType\": \"OrderCancelled\" }"));
    }
}
