package com.vanmool.paymentservice.repositories;

import com.vanmool.paymentservice.models.PaymentStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentStatusChangeRepository extends JpaRepository<PaymentStatusChange, UUID> {
    List<PaymentStatusChange> findByPaymentIdOrderByRecordedAtAsc(UUID paymentId);
}
