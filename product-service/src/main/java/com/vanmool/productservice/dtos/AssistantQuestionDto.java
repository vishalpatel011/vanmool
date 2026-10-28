package com.vanmool.productservice.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// A customer's question about the catalog, for example "Which products are from Dangs?"
public record AssistantQuestionDto(@NotBlank @Size(max = 300) String question) {
}
