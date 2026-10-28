package com.vanmool.productservice.controllers;

import com.vanmool.productservice.assistant.ListingAssistant;
import com.vanmool.productservice.dtos.ListingDraftDto;
import com.vanmool.productservice.dtos.ListingDraftRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Listing assistant", description = "SELLER, ADMIN: a draft listing written from a producer's notes by a language model")
@RestController
public class ListingAssistantController {
    private final ListingAssistant listingAssistant;

    public ListingAssistantController(ListingAssistant listingAssistant) {
        this.listingAssistant = listingAssistant;
    }

    @Operation(summary = "SELLER, ADMIN: a draft listing from a producer's notes; nothing is saved")
    @PostMapping("/products/listing-drafts")
    public ListingDraftDto draft(@Valid @RequestBody ListingDraftRequestDto request) {
        return listingAssistant.draft(request.notes());
    }
}
