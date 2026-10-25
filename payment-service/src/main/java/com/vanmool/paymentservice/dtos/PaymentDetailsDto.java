package com.vanmool.paymentservice.dtos;

import com.vanmool.paymentservice.models.PaymentStatusChange;

import java.time.Instant;
import java.util.List;

// GET /payments/{id}: the payment and its history, oldest first
public record PaymentDetailsDto(PaymentResponseDto payment, List<StatusChange> history) {
    public record StatusChange(String status, String source, String detail, Instant at) {
        public static StatusChange from(PaymentStatusChange change) {
            return new StatusChange(change.getStatus().name(), change.getSource().name(), change.getDetail(),
                    change.getRecordedAt());
        }
    }
}
