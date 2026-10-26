package com.vanmool.orderservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

// One step of an order's timeline: the status it reached, when, and a detail such as the tracking number. Rows are
// only ever added. The order is referred to by its id: the status changes are written with conditional UPDATEs, and
// no loaded Order entity is needed for them
@Getter
@Entity
@Table(name = "order_status_change")
public class OrderStatusChange {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;
    @Column(nullable = false)
    private Instant changedAt;
    private String detail;

    protected OrderStatusChange() {
    }

    public static OrderStatusChange of(UUID orderId, OrderStatus status, Instant changedAt, String detail) {
        OrderStatusChange change = new OrderStatusChange();
        change.orderId = orderId;
        change.status = status;
        change.changedAt = changedAt;
        change.detail = detail;
        return change;
    }
}
