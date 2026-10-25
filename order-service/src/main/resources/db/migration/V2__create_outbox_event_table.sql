-- Transactional outbox: an event is written here in the same transaction as the change it describes (an order placed or cancelled),
-- so the two are saved together or not at all. OutboxRelay then sends unpublished rows to Kafka and stamps
-- published_at. If Kafka is down, the rows wait here and are sent once it is back.

CREATE TABLE outbox_event
(
    id           BINARY(16)   NOT NULL,
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6),
    PRIMARY KEY (id),
    -- The relay's query: unpublished rows, oldest first
    INDEX idx_outbox_event_published_created (published_at, created_at)
) ENGINE = InnoDB;
