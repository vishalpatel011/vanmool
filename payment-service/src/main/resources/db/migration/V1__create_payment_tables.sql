-- Payments for order-service's orders. order_id and user_id are external references: no foreign keys to them.

CREATE TABLE payments
(
    id                 BINARY(16)     NOT NULL,
    order_id           BINARY(16)     NOT NULL,
    user_id            BINARY(16)     NOT NULL,
    amount             DECIMAL(12, 2) NOT NULL,
    currency           CHAR(3)        NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    -- RAZORPAY, STRIPE or FAKE; empty until a gateway has created the payment link
    gateway            VARCHAR(20),
    -- The gateway's id for the link: a Razorpay payment link (plink_...) or a Stripe Checkout Session (cs_...)
    gateway_reference  VARCHAR(100),
    payment_url        VARCHAR(500),
    -- The gateway's id of the money movement once paid (pay_... / pi_...), needed for refunds and support
    gateway_payment_id VARCHAR(100),
    customer_email     VARCHAR(255),
    expires_at         DATETIME(6)    NOT NULL,
    created_at         DATETIME(6)    NOT NULL,
    last_modified      DATETIME(6)    NOT NULL,
    version            BIGINT         NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_payments_order (order_id),
    INDEX idx_payments_status_created (status, created_at),
    INDEX idx_payments_gateway_reference (gateway, gateway_reference)
) ENGINE = InnoDB;

-- At most one open (CREATED) payment per order, even with two requests at the same moment. A functional index on an
-- expression that is the order id while the payment is open and NULL afterwards: NULLs never collide, so finished
-- payments do not count (MySQL 8.0.13+)
CREATE UNIQUE INDEX uk_payments_one_open_per_order ON payments ((CASE WHEN status = 'CREATED' THEN order_id END));

-- Every change of a payment's status, appended and never updated (the event sourcing idea): who changed it
-- (API, CALLBACK, WEBHOOK, RECONCILIATION) and why. The payments row holds only the latest status
CREATE TABLE payment_status_change
(
    id          BINARY(16)   NOT NULL,
    payment_id  BINARY(16)   NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    source      VARCHAR(20)  NOT NULL,
    detail      VARCHAR(500),
    recorded_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_payment_status_change_payment (payment_id, recorded_at),
    CONSTRAINT fk_payment_status_change_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
) ENGINE = InnoDB;

-- Gateway events already handled. Gateways retry webhooks and may send one twice; the primary key makes the second
-- delivery a no-op
CREATE TABLE processed_gateway_event
(
    id          VARCHAR(150) NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    received_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- Transactional outbox, as in user-auth-service and order-service
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
    INDEX idx_outbox_event_published_created (published_at, created_at)
) ENGINE = InnoDB;
