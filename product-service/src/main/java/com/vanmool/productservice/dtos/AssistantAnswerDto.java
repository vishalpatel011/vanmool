package com.vanmool.productservice.dtos;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// The shopping assistant's answer, and the products it was based on (the closest ones in the catalog), so a client
// can link to them. No products: nothing in the catalog was close enough to the question.
public record AssistantAnswerDto(String answer, List<ProductRef> products) {

    public record ProductRef(UUID id, String name, String price, String category, String originRegion) {
        public static ProductRef from(Document document) {
            Map<String, Object> metadata = document.getMetadata();
            return new ProductRef(UUID.fromString(document.getId()), text(metadata, "name"), text(metadata, "price"),
                    text(metadata, "category"), text(metadata, "originRegion"));
        }

        private static String text(Map<String, Object> metadata, String key) {
            Object value = metadata.get(key);
            return value == null || value.toString().isBlank() ? null : value.toString();
        }
    }
}
