package com.vanmool.productservice.models;

// One picture of a product. altText describes it for people using a screen reader, and when it cannot be loaded
public record ProductImage(String url, String altText) {
}
