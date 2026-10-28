package com.vanmool.productservice.controllers;

import com.vanmool.productservice.assistant.ShoppingAssistant;
import com.vanmool.productservice.dtos.AssistantAnswerDto;
import com.vanmool.productservice.dtos.AssistantQuestionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shopping assistant", description = "Questions about the catalog, answered from the matching products (RAG)")
@RestController
public class ShoppingAssistantController {
    private final ShoppingAssistant shoppingAssistant;

    public ShoppingAssistantController(ShoppingAssistant shoppingAssistant) {
        this.shoppingAssistant = shoppingAssistant;
    }

    @Operation(summary = "Ask about the catalog: the answer uses only the matching products, which are returned with it")
    @PostMapping("/assistant/questions")
    public AssistantAnswerDto ask(@Valid @RequestBody AssistantQuestionDto request) {
        return shoppingAssistant.answer(request.question());
    }
}
