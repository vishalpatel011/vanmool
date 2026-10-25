package com.vanmool.notificationservice.emails;

import com.vanmool.notificationservice.events.Events;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Writes the text of each email. Amounts are in Indian rupees with Indian digit grouping: ₹1,359.98, ₹1,00,000.00
@Component
public class EmailComposer {
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
        String body = """
                Hello,

                Your order %s (%s) has been cancelled. Nothing will be charged for it.
                """.formatted(event.orderId(), rupees(event.totalAmount())) + SIGNATURE;
        return new Email(event.customerEmail(), "Your Vanmool order " + event.orderId().toString().substring(0, 8)
                + " is cancelled", body);
    }

    private static String shortId(Events.OrderPlaced event) {
        return event.orderId().toString().substring(0, 8);
    }
}
