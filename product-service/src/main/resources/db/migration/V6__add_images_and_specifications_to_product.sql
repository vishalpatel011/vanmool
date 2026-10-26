-- Product details (PRD 2.2): several images and a list of specifications per product.
-- Both are JSON arrays in the product row, not tables of their own: they are only ever read and written together with
-- their product, so a page of products needs no further queries, and their order is simply the array's order.
--   images:         [{"url": "https://...", "altText": "..."}, ...]   the first one is the main image
--   specifications: [{"name": "Dimensions", "value": "30 x 20 cm"}, ...]
ALTER TABLE product
    ADD COLUMN images         JSON NOT NULL DEFAULT (JSON_ARRAY()),
    ADD COLUMN specifications JSON NOT NULL DEFAULT (JSON_ARRAY());

-- The one image a product had so far becomes its first image, described by the product's name
UPDATE product
SET images = JSON_ARRAY(JSON_OBJECT('url', image_url, 'altText', name))
WHERE image_url IS NOT NULL AND image_url <> '';

ALTER TABLE product DROP COLUMN image_url;
