package com.vanmool.productservice.assistant;

import com.vanmool.productservice.dtos.ListingDraftDto;
import com.vanmool.productservice.projections.CategorySummary;
import com.vanmool.productservice.repositories.CategoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

// Turns a producer's notes into a draft listing with a language model (Spring AI's ChatClient). Nothing is saved: the
// seller checks and edits the draft, then lists the product with POST /products. The model may use only facts that are
// in the notes; what the notes do not say stays empty, so a draft never claims a certification or a size by itself.
@Slf4j
@Service
public class ListingAssistant {
    // A template: {categories} is filled in with the categories the catalog already has
    static final String INSTRUCTIONS = """
            You help rural and tribal producers in Gujarat list their handmade and natural products on Vanmool, an online shop.
            From the producer's notes, write a listing draft in English that the producer will check before publishing.
            Rules:
            - Use only facts that are in the notes. Never invent prices, sizes, weights, certifications, awards or health claims.
            - If the notes do not say something, leave that field null.
            - name: a short product name, at most 8 words.
            - description: what the product is and how it is used, 2 to 3 sentences.
            - story: who made it, where and how, 1 to 3 sentences, warm but factual. No traditions, generations, nutrition or
              quality claims that the notes do not make.
            - materials: what it is made of, as a short list.
            - originRegion: the name of the village, town or district given in the notes (such as Ahwa or Sankheda), followed
              by ", Gujarat"; null if the notes name no place ("our village" names no place).
            - category: one of the existing categories if one fits, otherwise a short new category name.
            Existing categories: {categories}""";

    private final ChatClient chatClient;
    private final CategoryRepository categoryRepository;

    public ListingAssistant(ChatClient.Builder chatClientBuilder, CategoryRepository categoryRepository) {
        this.chatClient = chatClientBuilder.build();
        this.categoryRepository = categoryRepository;
    }

    public ListingDraftDto draft(String notes) {
        String categories = categoryRepository.findCategorySummaries().stream()
                .map(CategorySummary::getName)
                .collect(Collectors.joining(", "));
        ListingDraftDto draft;
        try {
            // entity() asks the model for JSON in the shape of ListingDraftDto and converts the answer
            draft = chatClient.prompt()
                    .system(system -> system.text(INSTRUCTIONS).param("categories", categories.isEmpty() ? "none yet" : categories))
                    .user(notes)
                    .call()
                    .entity(ListingDraftDto.class);
        } catch (RuntimeException e) {
            // The model's server is down or too slow, or its answer is not the JSON asked for: a temporary failure of
            // a dependency, whatever its exception type, never a draft built from half an answer
            log.warn("The listing assistant failed: {}", e.toString());
            throw new AssistantUnavailableException("The listing assistant is not available right now; please try again shortly", e);
        }
        if (draft == null || draft.name() == null || draft.name().isBlank()) {
            throw new AssistantUnavailableException("The listing assistant gave no usable draft; please try again", null);
        }
        return draft.withOriginFoundIn(notes).withinProductLimits();
    }
}
