-- The customer's email, copied from their token when the order is placed: the order's emails (also those sent when
-- the system cancels an unpaid order, with no customer request in hand) go to it
ALTER TABLE orders ADD COLUMN customer_email VARCHAR(255);

-- The unpaid-order expiry looks for PENDING_PAYMENT orders older than the payment window
CREATE INDEX idx_orders_status_created_at ON orders (status, created_at);
