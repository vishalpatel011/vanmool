package com.vanmool.productservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.productservice.services.StockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Unit test of the order-events listener with a mocked StockService. The real Kafka path, retries and the
// dead-letter topic are in OrderEventsIT.
@ExtendWith(MockitoExtension.class)
class OrderEventsListenerTest {
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    @Mock
    private StockService stockService;

    private OrderEventsListener listener() {
        return new OrderEventsListener(new ObjectMapper(), stockService);
    }

    @Test
    void orderCancelled_releasesTheOrdersStock() throws Exception {
        // Act: the JSON order-service publishes, with fields this service does not use
        listener().onOrderEvent("""
                { "eventId": "0b6f3c1e-8a0d-4f7e-9a51-3c2a6e8d9f10", "eventType": "OrderCancelled",
                  "occurredAt": "2026-10-25T10:00:00Z", "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                  "userId": "a8c1e6ff-556d-4dd2-a238-148b3fca351c", "customerEmail": "asha@example.com", "totalAmount": 999.98 }""");

        // Assert
        verify(stockService).release(ORDER_ID);
    }

    @Test
    void otherEvents_areIgnored() throws Exception {
        // Act
        listener().onOrderEvent("{ \"eventType\": \"OrderPlaced\", \"orderId\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\" }");
        listener().onOrderEvent("{ \"eventType\": \"SomethingNew\" }");

        // Assert
        verifyNoInteractions(stockService);
    }

    @Test
    void textThatIsNotJson_cannotBeRead() {
        assertThrows(JsonProcessingException.class, () -> listener().onOrderEvent("not json"));
        verifyNoInteractions(stockService);
    }

    @Test
    void cancellationWithoutAValidOrderId_isMalformed() {
        assertThrows(MalformedEventException.class,
                () -> listener().onOrderEvent("{ \"eventType\": \"OrderCancelled\", \"orderId\": \"not-a-uuid\" }"));
        assertThrows(MalformedEventException.class,
                () -> listener().onOrderEvent("{ \"eventType\": \"OrderCancelled\" }"));
        verifyNoInteractions(stockService);
    }
}
