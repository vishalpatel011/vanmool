package com.vanmool.notificationservice.emails;

import com.vanmool.notificationservice.events.Events;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

// Writes the text of each email. Amounts are in Indian rupees with Indian digit grouping: ₹1,359.98, ₹1,00,000.00
@Component
public class EmailComposer {
    // Times in Indian Standard Time: 26 Oct 2026, 3:45 PM IST
    private static final DateTimeFormatter RECEIPT_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a 'IST'", Locale.ENGLISH);
    private static final String SIGNATURE = """

            Vanmool: crafts and foods from the villages of Gujarat""";

    // The last three digits form one group, every two digits before them another (lakh, crore). Java's NumberFormat
    // only knows equal groups, so for "en-IN" it writes ₹100,000.00; this does it by hand
    String rupees(BigDecimal amount) {
        String plain = amount.setScale(2, RoundingMode.HALF_UP).abs().toPlainString();
        String rupees = plain.substring(0, plain.length() - 3);
        String paise = plain.substring(plain.length() - 3);
        StringBuilder grouped = new StringBuilder(rupees.substring(Math.max(0, rupees.length() - 3)));
        for (int end = rupees.length() - 3; end > 0; end -= 2) {
            grouped.insert(0, rupees.substring(Math.max(0, end - 2), end) + ",");
        }
        return (amount.signum() < 0 ? "-" : "") + "₹" + grouped + paise;
    }

    public Email welcome(Events.UserRegistered event) {
        String body = """
                Hello %s,

                Welcome to Vanmool. Your account is ready: you can browse baskets, wooden toys, ragi and millet foods \
                and more, each with the story of the people who make it.
                """.formatted(event.name()) + SIGNATURE;
        return new Email(event.email(), "Welcome to Vanmool", body);
    }

    // A security notice: if someone else changed the password, the owner learns of it at once
    public Email passwordChanged(Events.PasswordChanged event) {
        String changedAt = event.occurredAt() == null ? "just now"
                : "on " + RECEIPT_TIME.format(event.occurredAt().atZone(ZoneId.of("Asia/Kolkata")));
        String body = """
                Hello %s,

                The password of your Vanmool account was changed %s. Your other devices have been signed out.

                If you did not change it, someone else may know your password: please contact us straight away.
                """.formatted(event.name(), changedAt) + SIGNATURE;
        return new Email(event.email(), "Your Vanmool password was changed", body);
    }

    public Email orderConfirmation(Events.OrderPlaced event) {
        // One line per product, then the total, in two columns:   2 x Handcrafted Bamboo Basket       ₹999.98
        StringBuilder lines = new StringBuilder();
        for (Events.Item item : event.items()) {
            BigDecimal lineTotal = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
            // "\n", not %n: %n is the line separator of the machine running the service (\r\n on Windows)
            lines.append("  %2d x %-36s %14s\n".formatted(item.quantity(), item.name(), rupees(lineTotal)));
        }
        lines.append("  %-41s %14s".formatted("Total", rupees(event.totalAmount())));

        Events.Address address = event.shippingAddress();
        String addressLines = address.line1() + (address.line2() == null ? "" : "\n" + address.line2());
        String body = """
                Hello %s,

                Thank you for your order. We have received it and are waiting for your payment.

                Order %s

                %s

                It will be sent to:
                %s
                %s
                %s, %s %s
                Phone %s
                """.formatted(address.name(), event.orderId(), lines, address.name(), addressLines,
                address.city(), address.state(), address.pincode(), address.phone()) + SIGNATURE;
        return new Email(event.customerEmail(), "Your Vanmool order " + shortId(event) + " is placed", body);
    }

    public Email orderCancellation(Events.OrderCancelled event) {
        String why = "PAYMENT_TIMEOUT".equals(event.reason())
                ? " because its payment did not arrive in time; the products are back on sale"
                : "";
        String body = """
                Hello,

                Your order %s (%s) has been cancelled%s. Nothing will be charged for it.
                """.formatted(event.orderId(), rupees(event.totalAmount()), why) + SIGNATURE;
        return new Email(event.customerEmail(), "Your Vanmool order " + event.orderId().toString().substring(0, 8)
                + " is cancelled", body);
    }

    // Order tracking (PRD 4.3): who carries the order, and the number to follow it with on the carrier's site
    public Email orderShipped(Events.OrderShipped event) {
        String tracking = event.trackingNumber() == null ? ""
                : "\nTracking number: " + event.trackingNumber() + " (on " + event.carrier() + "'s website)";
        String body = """
                Hello,

                Your order %s is on its way. It has been handed to %s.%s

                You can follow it in your orders on Vanmool too.
                """.formatted(event.orderId(), event.carrier(), tracking) + SIGNATURE;
        return new Email(event.customerEmail(), "Your Vanmool order " + event.orderId().toString().substring(0, 8)
                + " is on its way", body);
    }

    public Email orderDelivered(Events.OrderDelivered event) {
        String body = """
                Hello,

                Your order %s has been delivered. Thank you for buying from the producers of Vanmool.
                """.formatted(event.orderId()) + SIGNATURE;
        return new Email(event.customerEmail(), "Your Vanmool order " + event.orderId().toString().substring(0, 8)
                + " is delivered", body);
    }

    // The receipt (PRD 5.3): what was paid, for which order, how, and the gateway's reference for any question
    public Email paymentReceipt(Events.PaymentSucceeded event) {
        String paidAt = event.occurredAt() == null ? ""
                : RECEIPT_TIME.format(event.occurredAt().atZone(ZoneId.of("Asia/Kolkata")));
        String body = """
                Hello,

                We have received your payment. Your order is confirmed and will be prepared for dispatch.

                Amount paid       %s
                Order             %s
                Paid on           %s
                Paid through      %s
                Payment reference %s
                """.formatted(rupees(event.amount()), event.orderId(), paidAt, gatewayName(event.gateway()),
                event.gatewayPaymentId()) + SIGNATURE;
        return new Email(event.customerEmail(), "Payment received for your Vanmool order "
                + event.orderId().toString().substring(0, 8), body);
    }

    public Email paymentFailed(Events.PaymentFailed event) {
        String what = "EXPIRED".equals(event.reason()) ? "The payment link for your order expired before it was paid"
                : "Your payment for your order did not go through";
        String body = """
                Hello,

                %s (order %s, %s). No money was taken.
                You can start the payment again from your order; the order is kept for one hour after it was placed.
                """.formatted(what, event.orderId(), rupees(event.amount())) + SIGNATURE;
        return new Email(event.customerEmail(), "Your Vanmool payment did not go through", body);
    }

    private static String gatewayName(String gateway) {
        return switch (gateway == null ? "" : gateway) {
            case "RAZORPAY" -> "Razorpay";
            case "STRIPE" -> "Stripe";
            case "FAKE" -> "Test gateway";
            default -> gateway;
        };
    }

    private static String shortId(Events.OrderPlaced event) {
        return event.orderId().toString().substring(0, 8);
    }
}
