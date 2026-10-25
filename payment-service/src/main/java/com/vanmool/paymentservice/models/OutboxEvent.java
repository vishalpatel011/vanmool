package com.vanmool.paymentservice.models;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// An event waiting to be sent to Kafka, or already sent (migration V1). The id is the event's own eventId, which
// consumers use to recognise an event they have already handled.
@Getter
@Setter
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    @Id
    private UUID id;
    @Column(nullable = false, length = 100)
    private String topic;
    // The Kafka message key: events with the same key go to the same partition, so they are read in order
    @Column(nullable = false, length = 100)
    private String messageKey;
    @Column(nullable = false, length = 50)
    private String eventType;
    // Stored in a MySQL JSON column, which rejects anything that is not valid JSON
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant publishedAt;
}
