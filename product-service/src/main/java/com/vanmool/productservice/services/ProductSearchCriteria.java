package com.vanmool.productservice.services;

import java.math.BigDecimal;

// The optional filters of GET /products. A null or blank value means "do not filter on this".
// text: words to find in the name, description, materials, origin or story; origin: part of the origin region
public record ProductSearchCriteria(String text, String category, String origin, BigDecimal minPrice, BigDecimal maxPrice) {
}
