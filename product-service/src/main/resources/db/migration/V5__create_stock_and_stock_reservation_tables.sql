-- Stock: how many units of each product can still be sold, and the reservations that orders hold on them.
-- Kept apart from the product table: stock changes with every order while the product's description rarely does,
-- and only the description is cached in Redis. A product without a stock row has no units to sell.

CREATE TABLE stock
(
    product_id    BINARY(16)  NOT NULL,
    quantity      INT         NOT NULL,
    last_modified DATETIME(6) NOT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES product (id),
    -- A second guard behind the conditional UPDATEs in StockRepository: MySQL 8 enforces CHECK constraints
    CONSTRAINT chk_stock_quantity_not_negative CHECK (quantity >= 0)
) ENGINE = InnoDB;

-- One reservation per order. order_id is the id order-service gives the order: an external reference, so there is
-- no foreign key. Rows are never deleted: a released reservation stays, marked RELEASED.
CREATE TABLE stock_reservation
(
    order_id      BINARY(16)  NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    last_modified DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE = InnoDB;

-- The units an order holds of each product, with the name and price at the moment they were reserved
CREATE TABLE stock_reservation_item
(
    order_id     BINARY(16)     NOT NULL,
    product_id   BINARY(16)     NOT NULL,
    product_name VARCHAR(255)   NOT NULL,
    unit_price   DECIMAL(10, 2) NOT NULL,
    quantity     INT            NOT NULL,
    PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_stock_reservation_item_reservation FOREIGN KEY (order_id) REFERENCES stock_reservation (order_id),
    CONSTRAINT fk_stock_reservation_item_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;
