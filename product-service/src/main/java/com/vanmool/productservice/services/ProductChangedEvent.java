package com.vanmool.productservice.services;

import java.util.UUID;

// A product was created, changed or deleted. Published inside the change's transaction; listeners that must only see
// committed changes (the assistant's catalog index) listen after the commit.
public record ProductChangedEvent(UUID productId) {
}
