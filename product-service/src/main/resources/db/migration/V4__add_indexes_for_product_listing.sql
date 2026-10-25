-- GET /products always filters on deleted and sorts by created_at (the default, newest first) or by price.
-- With these indexes MySQL can read one page of products in the requested order instead of sorting them all.

CREATE INDEX idx_product_deleted_created_at ON product (deleted, created_at);
CREATE INDEX idx_product_deleted_price ON product (deleted, price);
