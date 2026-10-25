package com.vanmool.paymentservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One attempt to pay one order. An order can have several (one expired, the next paid), but only one open at a time.
// The id is also the reference sent to the gateway (Razorpay reference_id, Stripe client_reference_id), so every
// gateway event can be matched to its payment.
@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID orderId;
    @Column(nullable = false)
    private UUID userId;
    // The order's total, from order-service; never from the client
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    // ISO 4217 code, always three letters: CHAR(3) in the table
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GatewayName gateway;
    @Column(length = 100)
    private String gatewayReference;
    @Column(length = 500)
    private String paymentUrl;
    @Column(length = 100)
    private String gatewayPaymentId;
    private String customerEmail;
    @Column(nullable = false)
    private Instant expiresAt;
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant lastModified;
    // Also tells Spring Data that a payment with an id but no version yet is new, so save() inserts it directly
    @Version
    private Long version;

    // In the gateway's smallest unit: paise for INR (499.99 -> 49999)
    public long amountInMinorUnits() {
        return amount.movePointRight(2).longValueExact();
    }
}
