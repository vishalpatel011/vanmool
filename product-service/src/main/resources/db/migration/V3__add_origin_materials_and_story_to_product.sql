-- Provenance details that set Vanmool apart from a general marketplace: where a product comes from,
-- what it is made of, and the story of the people who make it. All optional, so existing products stay valid.

ALTER TABLE product
    ADD COLUMN origin_region VARCHAR(255),
    ADD COLUMN materials     VARCHAR(255),
    ADD COLUMN story         VARCHAR(2000);
