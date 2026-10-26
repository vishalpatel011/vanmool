-- Order tracking (PRD 4.3): how a paid order was sent, and every status it has been in, with the time.

-- Set when the order is handed to the carrier. The tracking number is optional: a cooperative that delivers nearby
-- orders itself has none
ALTER TABLE orders
    ADD COLUMN carrier         VARCHAR(100),
    ADD COLUMN tracking_number VARCHAR(50);

-- One row per status the order has reached, never updated: the customer's tracking timeline, and an audit trail
CREATE TABLE order_status_change
(
    id         BINARY(16)   NOT NULL,
    order_id   BINARY(16)   NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    changed_at DATETIME(6)  NOT NULL,
    -- For example the carrier and tracking number, or why the order was cancelled
    detail     VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_status_change_order FOREIGN KEY (order_id) REFERENCES orders (id),
    -- An order's timeline, oldest first
    INDEX idx_order_status_change_order_changed_at (order_id, changed_at)
) ENGINE = InnoDB;

-- Orders placed before this script get a history from what is known: placed at created_at and, if they have moved
-- on since, their current status at last_modified (after it is placed, an order changes nothing but its status)
INSERT INTO order_status_change (id, order_id, status, changed_at)
SELECT UUID_TO_BIN(UUID()), id, 'PENDING_PAYMENT', created_at
FROM orders;

INSERT INTO order_status_change (id, order_id, status, changed_at)
SELECT UUID_TO_BIN(UUID()), id, status, last_modified
FROM orders
WHERE status <> 'PENDING_PAYMENT';
