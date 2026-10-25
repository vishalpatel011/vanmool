-- Initial product-service schema, matching the Category and Product entities.
-- Ids are random UUIDs stored as BINARY(16); prices are exact DECIMAL(10,2) values.

CREATE TABLE category
(
    id            BINARY(16)   NOT NULL,
    name          VARCHAR(255) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    last_modified DATETIME(6)  NOT NULL,
    deleted       BIT(1)       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_name UNIQUE (name)
) ENGINE = InnoDB;

CREATE TABLE product
(
    id            BINARY(16)     NOT NULL,
    name          VARCHAR(255)   NOT NULL,
    description   VARCHAR(255),
    image_url     VARCHAR(255),
    price         DECIMAL(10, 2) NOT NULL,
    category_id   BINARY(16),
    created_at    DATETIME(6)    NOT NULL,
    last_modified DATETIME(6)    NOT NULL,
    deleted       BIT(1)         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE = InnoDB;
