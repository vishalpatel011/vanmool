package com.vanmool.productservice.dtos;

import java.util.Locale;
import java.util.Set;

// A draft listing written by the listing assistant, for the seller to check before POST /products. Any field may be
// null: the notes did not say it. Its fields are the product's, so the seller can send them on as they are.
public record ListingDraftDto(String name, String description, String story, String materials, String originRegion,
                              String category) {

    private static final Set<String> NOT_A_PLACE_NAME = Set.of("village", "town", "city", "district", "farm", "home",
            "area", "region", "place");

    // A model can name a place the notes do not name, or make one of "our village": the place is kept only when the
    // notes contain it and it is a name, not a word like "village"
    public ListingDraftDto withOriginFoundIn(String notes) {
        if (originRegion == null) {
            return this;
        }
        String place = originRegion.split(",")[0].strip().toLowerCase(Locale.ROOT).replaceFirst("^(our|my|the) ", "");
        boolean named = !place.isEmpty() && !NOT_A_PLACE_NAME.contains(place) && notes.toLowerCase(Locale.ROOT).contains(place);
        return named ? this : new ListingDraftDto(name, description, story, materials, null, category);
    }

    // A model can write more than a product may hold: cut each field to the product's limit
    public ListingDraftDto withinProductLimits() {
        return new ListingDraftDto(cut(name, 255), cut(description, 2000), cut(story, 2000), cut(materials, 255),
                cut(originRegion, 255), cut(category, 255));
    }

    private static String cut(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength).strip();
    }
}
