package com.vanmool.productservice.assistant;

import com.vanmool.productservice.dtos.AssistantAnswerDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingAssistantTest {
    // Stands in for the language model and keeps the prompt it was given
    private static final class FakeChatModel implements ChatModel {
        private String answer = "The Handcrafted Bamboo Basket (₹499.99) comes from Dangs.";
        private RuntimeException failure;
        private Prompt prompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        String messageText(MessageType type) {
            return prompt.getInstructions().stream().filter(message -> message.getMessageType() == type)
                    .map(Message::getText).findFirst().orElseThrow();
        }
    }

    private static final String QUESTION = "Which products are from Dangs?";
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final Document BASKET = Document.builder().id(BASKET_ID.toString())
            .text(CatalogIndex.DOCUMENT_PREFIX + "Handcrafted Bamboo Basket. Category: Traditional Crafts. From: Dangs, Gujarat. Price: ₹499.99.")
            .metadata(Map.of("name", "Handcrafted Bamboo Basket", "price", "499.99", "category", "Traditional Crafts",
                    "originRegion", "Dangs, Gujarat"))
            .build();

    private final FakeChatModel model = new FakeChatModel();
    private final CatalogIndex catalogIndex = mock(CatalogIndex.class);
    private final ShoppingAssistant assistant = new ShoppingAssistant(ChatClient.builder(model), catalogIndex, 4, 0.5);

    @Test
    void theAnswer_comesFromTheMatchingProductsOnly_whichAreReturnedWithIt() {
        // Arrange
        when(catalogIndex.search(QUESTION, 4, 0.5)).thenReturn(List.of(BASKET));

        // Act
        AssistantAnswerDto answer = assistant.answer(QUESTION);

        // Assert: the model was given the rules and the product's facts (without the embedding prefix), and the question
        assertEquals("The Handcrafted Bamboo Basket (₹499.99) comes from Dangs.", answer.answer());
        assertEquals(List.of(new AssistantAnswerDto.ProductRef(BASKET_ID, "Handcrafted Bamboo Basket", "499.99",
                "Traditional Crafts", "Dangs, Gujarat")), answer.products());
        String instructions = model.messageText(MessageType.SYSTEM);
        assertTrue(instructions.contains("using only the products listed below"), instructions);
        assertTrue(instructions.endsWith("- Handcrafted Bamboo Basket. Category: Traditional Crafts. From: Dangs, Gujarat. Price: ₹499.99."), instructions);
        assertFalse(instructions.contains(CatalogIndex.DOCUMENT_PREFIX));
        assertEquals(QUESTION, model.messageText(MessageType.USER));
    }

    @Test
    void noMatchingProduct_isSaidPlainly_withoutAskingTheModel() {
        when(catalogIndex.search("Do you sell laptops?", 4, 0.5)).thenReturn(List.of());

        AssistantAnswerDto answer = assistant.answer("Do you sell laptops?");

        assertEquals(ShoppingAssistant.NOTHING_FOUND, answer.answer());
        assertTrue(answer.products().isEmpty());
        assertNull(model.prompt);
    }

    @Test
    void aModelOrIndexThatIsDown_isUnavailable() {
        when(catalogIndex.search(QUESTION, 4, 0.5)).thenReturn(List.of(BASKET));
        model.failure = new ResourceAccessException("Connection refused: localhost:11434");
        assertThrows(AssistantUnavailableException.class, () -> assistant.answer(QUESTION));

        when(catalogIndex.search(QUESTION, 4, 0.5)).thenThrow(new ResourceAccessException("Connection refused"));
        assertThrows(AssistantUnavailableException.class, () -> assistant.answer(QUESTION));
    }
}
