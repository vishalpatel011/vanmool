package com.vanmool.paymentservice.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

// A gateway event already handled, under "GATEWAY:eventId". Inserted in the same transaction as the change the event
// causes; a second delivery of the same event fails on the primary key and changes nothing.
// Persistable with isNew() = true: save() always INSERTs (and so fails on a duplicate) instead of merging
@Getter
@NoArgsConstructor
@Entity
@Table(name = "processed_gateway_event")
public class ProcessedGatewayEvent implements Persistable<String> {
    @Id
    @Column(length = 150)
    private String id;
    @Column(nullable = false, length = 100)
    private String eventType;
    @Column(nullable = false)
    private Instant receivedAt;

    public ProcessedGatewayEvent(String id, String eventType, Instant receivedAt) {
        this.id = id;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
