package com.vanmool.productservice.models;

// One line of a product's specifications, such as "Dimensions" / "30 x 20 cm" or "Shelf life" / "6 months"
public record ProductSpecification(String name, String value) {
}
