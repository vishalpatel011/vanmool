package com.vanmool.paymentservice.repositories;

import com.vanmool.paymentservice.models.GatewayName;
import com.vanmool.paymentservice.models.Payment;
import com.vanmool.paymentservice.models.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByOrderIdAndStatus(UUID orderId, PaymentStatus status);

    Optional<Payment> findByIdAndUserId(UUID id, UUID userId);

    Optional<Payment> findByGatewayAndGatewayReference(GatewayName gateway, String gatewayReference);

    boolean existsByOrderIdAndStatus(UUID orderId, PaymentStatus status);

    // Reconciliation: payments in a status that were created in a time window
    List<Payment> findByStatusAndCreatedAtBetween(PaymentStatus status, Instant from, Instant to);

    // Moves a payment out of CREATED only if it is still CREATED, in one UPDATE: a webhook, the callback and the
    // reconciliation can all learn about the same payment at the same moment, and only one of them may act on it.
    // Returns 1 if this call moved it, 0 if another one already had
    @Modifying
    @Query("""
            UPDATE Payment p SET p.status = :to, p.gatewayPaymentId = :gatewayPaymentId,
                                 p.version = p.version + 1, p.lastModified = CURRENT_TIMESTAMP
            WHERE p.id = :id AND p.status = com.vanmool.paymentservice.models.PaymentStatus.CREATED""")
    int finish(@Param("id") UUID id, @Param("to") PaymentStatus to, @Param("gatewayPaymentId") String gatewayPaymentId);
}
