package com.vanmool.notificationservice.emails;

import com.vanmool.notificationservice.events.Events;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The text of each email, checked exactly where it matters: who it goes to, the subject, amounts in rupees
class EmailComposerTest {
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private final EmailComposer composer = new EmailComposer();

    private static Events.Address address(String line2) {
        return new Events.Address("Asha Gamit", "9876543210", "12 Station Road", line2, "Ahwa", "Gujarat", "394710");
    }

    @Test
    void rupees_useIndianDigitGrouping() {
        assertEquals("₹0.30", composer.rupees(new BigDecimal("0.3")));
        assertEquals("₹999.98", composer.rupees(new BigDecimal("999.98")));
        assertEquals("₹1,359.98", composer.rupees(new BigDecimal("1359.98")));
        assertEquals("₹1,00,000.00", composer.rupees(new BigDecimal("100000")));
        assertEquals("₹12,34,56,789.50", composer.rupees(new BigDecimal("123456789.5")));
        assertEquals("-₹120.00", composer.rupees(new BigDecimal("-120")));
    }

    @Test
    void welcome_greetsTheNewUserByName() {
        // Act
        Email email = composer.welcome(new Events.UserRegistered(UUID.randomUUID(), UUID.randomUUID(), "Asha Gamit",
                "asha@example.com"));

        // Assert
        assertEquals("asha@example.com", email.to());
        assertEquals("Welcome to Vanmool", email.subject());
        assertTrue(email.body().startsWith("Hello Asha Gamit,"));
    }

    @Test
    void passwordChanged_saysWhen_inIst_andWhatToDoIfItWasNotThem() {
        // 10:15 UTC is 3:45 PM in India
        Email email = composer.passwordChanged(new Events.PasswordChanged(UUID.randomUUID(),
                java.time.Instant.parse("2026-10-26T10:15:00Z"), UUID.randomUUID(), "Asha Gamit", "asha@example.com"));

        assertEquals("asha@example.com", email.to());
        assertEquals("Your Vanmool password was changed", email.subject());
        assertTrue(email.body().startsWith("Hello Asha Gamit,"), email.body());
        assertTrue(email.body().contains("was changed on 26 Oct 2026, 3:45 PM IST. Your other devices have been signed out."),
                email.body());
        assertTrue(email.body().contains("If you did not change it"), email.body());
    }

    @Test
    void passwordReset_holdsTheLink_andSaysUntilWhenItWorks_inIst() {
        // 10:30 UTC is 4:00 PM in India
        Email email = composer.passwordReset(new Events.PasswordResetRequested(UUID.randomUUID(), "Asha Gamit",
                "asha@example.com", "http://localhost:8081/auth/password-reset/confirm?token=abc123",
                java.time.Instant.parse("2026-10-26T10:30:00Z")));

        assertEquals("asha@example.com", email.to());
        assertEquals("Reset your Vanmool password", email.subject());
        assertTrue(email.body().contains("\n\nhttp://localhost:8081/auth/password-reset/confirm?token=abc123\n\n"), email.body());
        assertTrue(email.body().contains("The link works once, until 26 Oct 2026, 4:00 PM IST."), email.body());
        assertTrue(email.body().contains("you can ignore this email: your password stays as it is"), email.body());
    }

    @Test
    void orderConfirmation_listsEachProductWithItsLineTotal_theTotal_andTheAddress() {
        // Act
        Email email = composer.orderConfirmation(new Events.OrderPlaced(UUID.randomUUID(), ORDER_ID, "asha@example.com",
                List.of(new Events.Item("Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2),
                        new Events.Item("Ragi Biscuits", new BigDecimal("120.00"), 3)),
                new BigDecimal("1359.98"), address("Near the bus stand")));

        // Assert
        assertEquals("asha@example.com", email.to());
        assertEquals("Your Vanmool order 7c9e6679 is placed", email.subject());
        String body = email.body();
        assertTrue(body.contains("Order 7c9e6679-7425-40de-944b-e07fc1f90ae7"), body);
        // Two columns: names padded to one width, amounts right-aligned
        assertTrue(body.contains("   2 x Handcrafted Bamboo Basket" + " ".repeat(19) + "₹999.98\n"), body);
        assertTrue(body.contains("   3 x Ragi Biscuits" + " ".repeat(31) + "₹360.00\n"), body);
        assertTrue(body.contains("  Total" + " ".repeat(42) + "₹1,359.98\n"), body);
        assertTrue(body.contains("""
                It will be sent to:
                Asha Gamit
                12 Station Road
                Near the bus stand
                Ahwa, Gujarat 394710
                Phone 9876543210"""), body);
    }

    @Test
    void orderConfirmation_withoutASecondAddressLine_hasNoEmptyLine() {
        // Act
        Email email = composer.orderConfirmation(new Events.OrderPlaced(UUID.randomUUID(), ORDER_ID, "asha@example.com",
                List.of(new Events.Item("Ragi Biscuits", new BigDecimal("120.00"), 1)), new BigDecimal("120.00"),
                address(null)));

        // Assert
        assertTrue(email.body().contains("12 Station Road\nAhwa, Gujarat 394710"), email.body());
        assertFalse(email.body().contains("null"), email.body());
    }

    @Test
    void cancellation_namesTheOrderAndItsAmount() {
        // Act
        Email email = composer.orderCancellation(new Events.OrderCancelled(UUID.randomUUID(), ORDER_ID,
                "asha@example.com", new BigDecimal("999.98"), "CUSTOMER"));

        // Assert
        assertEquals("Your Vanmool order 7c9e6679 is cancelled", email.subject());
        assertTrue(email.body().contains("Your order 7c9e6679-7425-40de-944b-e07fc1f90ae7 (₹999.98) has been cancelled."),
                email.body());
    }

    @Test
    void cancellationForAMissingPayment_saysSo() {
        Email email = composer.orderCancellation(new Events.OrderCancelled(UUID.randomUUID(), ORDER_ID,
                "asha@example.com", new BigDecimal("999.98"), "PAYMENT_TIMEOUT"));

        assertTrue(email.body().contains("has been cancelled because its payment did not arrive in time"), email.body());
    }

    @Test
    void shipped_namesTheCarrierAndTrackingNumber() {
        Email email = composer.orderShipped(new Events.OrderShipped(UUID.randomUUID(), ORDER_ID, "asha@example.com",
                "India Post", "EE123456789IN"));

        assertEquals("Your Vanmool order 7c9e6679 is on its way", email.subject());
        assertTrue(email.body().contains("It has been handed to India Post.\n"
                + "Tracking number: EE123456789IN (on India Post's website)\n"), email.body());
    }

    @Test
    void shippedWithoutATrackingNumber_hasNoTrackingLine_andDelivered_saysSo() {
        Email shipped = composer.orderShipped(new Events.OrderShipped(UUID.randomUUID(), ORDER_ID, "asha@example.com",
                "Dangs Artisan Cooperative", null));
        Email delivered = composer.orderDelivered(new Events.OrderDelivered(UUID.randomUUID(), ORDER_ID, "asha@example.com"));

        assertTrue(shipped.body().contains("It has been handed to Dangs Artisan Cooperative.\n\n"), shipped.body());
        assertFalse(shipped.body().contains("Tracking number"), shipped.body());
        assertEquals("Your Vanmool order 7c9e6679 is delivered", delivered.subject());
        assertTrue(delivered.body().contains("Your order 7c9e6679-7425-40de-944b-e07fc1f90ae7 has been delivered."),
                delivered.body());
    }

    @Test
    void receipt_showsTheAmountOrderTimeInIstGatewayAndReference() {
        // 10:15 UTC is 3:45 PM in India
        Email email = composer.paymentReceipt(new Events.PaymentSucceeded(UUID.randomUUID(),
                java.time.Instant.parse("2026-10-26T10:15:00Z"), UUID.randomUUID(), ORDER_ID, "asha@example.com",
                new BigDecimal("1359.98"), "RAZORPAY", "pay_Test456"));

        assertEquals("Payment received for your Vanmool order 7c9e6679", email.subject());
        assertTrue(email.body().contains("Amount paid       ₹1,359.98\n"), email.body());
        assertTrue(email.body().contains("Paid on           26 Oct 2026, 3:45 PM IST\n"), email.body());
        assertTrue(email.body().contains("Paid through      Razorpay\n"), email.body());
        assertTrue(email.body().contains("Payment reference pay_Test456\n"), email.body());
    }

    @Test
    void failedPayment_saysNoMoneyWasTaken_andHowToTryAgain() {
        Email expired = composer.paymentFailed(new Events.PaymentFailed(UUID.randomUUID(), ORDER_ID, "asha@example.com",
                new BigDecimal("999.98"), "EXPIRED"));

        assertEquals("Your Vanmool payment did not go through", expired.subject());
        assertTrue(expired.body().contains("The payment link for your order expired before it was paid"), expired.body());
        assertTrue(expired.body().contains("No money was taken."), expired.body());
    }
}
