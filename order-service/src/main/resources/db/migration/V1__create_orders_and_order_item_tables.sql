-- Orders for order-service. Ids are random UUIDs stored as BINARY(16).
-- user_id and product_id refer to other services (user-auth-service, product-service): external references, so
-- there are no foreign keys to them. Names and prices are copied into the order, because the order must keep the
-- price it was placed at even after the product changes.

CREATE TABLE orders
(
    id               BINARY(16)     NOT NULL,
    user_id          BINARY(16)     NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    total_amount     DECIMAL(12, 2) NOT NULL,
    -- The client's Idempotency-Key header, if it sent one: the same key from the same user never makes a second order
    idempotency_key  VARCHAR(100),
    shipping_name    VARCHAR(100)   NOT NULL,
    shipping_phone   VARCHAR(10)    NOT NULL,
    shipping_line1   VARCHAR(255)   NOT NULL,
    shipping_line2   VARCHAR(255),
    shipping_city    VARCHAR(100)   NOT NULL,
    shipping_state   VARCHAR(100)   NOT NULL,
    shipping_pincode VARCHAR(6)     NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    last_modified    DATETIME(6)    NOT NULL,
    -- Optimistic locking: every change increments it, and a change based on an older version fails
    version          BIGINT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_user_idempotency_key UNIQUE (user_id, idempotency_key),
    -- Order history: one user's orders, newest first
    INDEX idx_orders_user_created_at (user_id, created_at)
) ENGINE = InnoDB;

CREATE TABLE order_item
(
    id           BINARY(16)     NOT NULL,
    order_id     BINARY(16)     NOT NULL,
    product_id   BINARY(16)     NOT NULL,
    product_name VARCHAR(255)   NOT NULL,
    unit_price   DECIMAL(10, 2) NOT NULL,
    quantity     INT            NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB;
