-- A product description tells how and where the product is made; 255 characters is too short for that.
-- Widening a VARCHAR keeps every existing value.

ALTER TABLE product
    MODIFY description VARCHAR(2000);
