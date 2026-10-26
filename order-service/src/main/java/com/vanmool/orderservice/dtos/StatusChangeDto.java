package com.vanmool.orderservice.dtos;

import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.OrderStatusChange;

import java.time.Instant;

public record StatusChangeDto(OrderStatus status, Instant changedAt, String detail) {
    public static StatusChangeDto from(OrderStatusChange change) {
        return new StatusChangeDto(change.getStatus(), change.getChangedAt(), change.getDetail());
    }
}
