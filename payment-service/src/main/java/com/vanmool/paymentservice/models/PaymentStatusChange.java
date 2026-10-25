package com.vanmool.paymentservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// One row per status a payment went through, appended and never changed: the payment's history. Rows are only
// inserted, so the history cannot be rewritten by a later update (the event sourcing idea from the lecture)
@Getter
@NoArgsConstructor
@Entity
@Table(name = "payment_status_change")
public class PaymentStatusChange {
    // ORDER_EVENT: order-service cancelled the order (OrderCancelled), so its open link was closed
    public enum Source { API, CALLBACK, WEBHOOK, RECONCILIATION, ORDER_EVENT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private UUID paymentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;
    @Column(length = 500)
    private String detail;
    @Column(nullable = false)
    private Instant recordedAt;

    public PaymentStatusChange(UUID paymentId, PaymentStatus status, Source source, String detail, Instant recordedAt) {
        this.paymentId = paymentId;
        this.status = status;
        this.source = source;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.recordedAt = recordedAt;
    }
}
