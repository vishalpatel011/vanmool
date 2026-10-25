package com.vanmool.paymentservice.dtos;

// What one reconciliation run did: payments checked with their gateway, statuses changed because the gateway knew
// more than this service, and mismatches that need a person (for example money taken for an expired payment)
public record ReconciliationReportDto(int checked, int changed, int mismatches, int gatewayErrors) {
}
