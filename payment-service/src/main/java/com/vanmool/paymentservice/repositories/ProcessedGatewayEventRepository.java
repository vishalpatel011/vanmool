package com.vanmool.paymentservice.repositories;

import com.vanmool.paymentservice.models.ProcessedGatewayEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedGatewayEventRepository extends JpaRepository<ProcessedGatewayEvent, String> {
}
