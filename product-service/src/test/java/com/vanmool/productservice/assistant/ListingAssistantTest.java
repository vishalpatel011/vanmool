package com.vanmool.productservice.assistant;

import com.vanmool.productservice.dtos.ListingDraftDto;
import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.repositories.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListingAssistantTest {
    // Stands in for the language model: answers with a prepared text, or fails, and keeps the prompt it was given
    private static final class FakeChatModel implements ChatModel {
        private String answer;
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

    private static final String NOTES = "wooden spinning top, turned on a hand lathe by my father in Sankheda, coloured with lac";

    private final FakeChatModel model = new FakeChatModel();
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private ListingAssistant assistant;

    @BeforeEach
    void setUp() {
        when(categoryRepository.findCategorySummaries()).thenReturn(List.of(
                new CategorySummary("Natural Foods", 1L), new CategorySummary("Traditional Crafts", 2L)));
        assistant = new ListingAssistant(ChatClient.builder(model), categoryRepository);
    }

    @Test
    void theModelsJsonBecomesTheDraft_andItsPromptCarriesTheRules_theCategories_andTheNotes() {
        // Arrange
        model.answer = """
                { "name": "Hand-Turned Wooden Spinning Top", "description": "A wooden top turned on a hand lathe.",
                  "story": "Turned by the producer's father in Sankheda.", "materials": "Wood, lac",
                  "originRegion": "Sankheda, Gujarat", "category": "Traditional Crafts" }""";

        // Act
        ListingDraftDto draft = assistant.draft(NOTES);

        // Assert
        assertEquals(new ListingDraftDto("Hand-Turned Wooden Spinning Top", "A wooden top turned on a hand lathe.",
                "Turned by the producer's father in Sankheda.", "Wood, lac", "Sankheda, Gujarat", "Traditional Crafts"), draft);
        String instructions = model.messageText(MessageType.SYSTEM);
        assertTrue(instructions.contains("Use only facts that are in the notes"), instructions);
        assertTrue(instructions.endsWith("Existing categories: Natural Foods, Traditional Crafts"), instructions);
        // The notes go as the user's message; Spring AI adds the JSON format it wants after them
        assertTrue(model.messageText(MessageType.USER).startsWith(NOTES));
    }

    @Test
    void whatTheNotesDoNotSay_staysEmpty_andTooLongFieldsAreCut() {
        // Arrange: no place named, an empty story, and a name longer than a product's 255 characters
        model.answer = """
                { "name": "%s", "description": "Hand-woven.", "story": " ", "materials": null,
                  "originRegion": null, "category": "Traditional Crafts" }""".formatted("Basket ".repeat(60));

        // Act
        ListingDraftDto draft = assistant.draft("bamboo basket, hand-woven");

        // Assert
        assertEquals(255, draft.name().length());
        assertNull(draft.story());
        assertNull(draft.originRegion());
    }

    @Test
    void aPlaceTheNotesDoNotName_isDropped_whileANamedPlaceIsKept() {
        // "our village" is no place name; Bhuj is not in the notes (a model made it up); Sankheda is in the notes
        String answer = """
                { "name": "Ragi Biscuits", "description": "Biscuits.", "originRegion": "%s", "category": "Natural Foods" }""";

        model.answer = answer.formatted("our village, Gujarat");
        assertNull(assistant.draft("ragi biscuits, baked by women in our village").originRegion());

        model.answer = answer.formatted("Bhuj, Gujarat");
        assertNull(assistant.draft("bamboo basket from Ahwa").originRegion());

        model.answer = answer.formatted("Sankheda, Gujarat");
        assertEquals("Sankheda, Gujarat", assistant.draft(NOTES).originRegion());
    }

    @Test
    void aModelThatCannotBeReached_orAnswersNoJson_orNoName_isUnavailable_neverAHalfDraft() {
        model.failure = new ResourceAccessException("Connection refused: localhost:11434");
        assertThrows(AssistantUnavailableException.class, () -> assistant.draft(NOTES));

        model.failure = null;
        model.answer = "Here is a nice listing for your spinning top!";
        assertThrows(AssistantUnavailableException.class, () -> assistant.draft(NOTES));

        model.answer = "{ \"name\": \"\", \"description\": \"A top.\" }";
        assertThrows(AssistantUnavailableException.class, () -> assistant.draft(NOTES));
    }
}
