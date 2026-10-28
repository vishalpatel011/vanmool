package com.vanmool.productservice.assistant;

import com.vanmool.productservice.dtos.AssistantAnswerDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

// Answers a customer's question from the catalog (retrieval-augmented generation): the products closest in meaning to
// the question are found in the catalog index, and the language model answers using only those products, so it cannot
// recommend something Vanmool does not sell or invent a price. With no product close enough, the answer says so
// without asking the model at all.
@Slf4j
@Service
public class ShoppingAssistant {
    static final String NOTHING_FOUND = "I could not find a Vanmool product that matches your question.";

    static final String INSTRUCTIONS = """
            You are the shopping assistant of Vanmool, an online shop for handmade and natural products from rural and
            tribal producers in Gujarat. Answer the customer's question using only the products listed below.
            Rules:
            - Name the products you recommend, with their price in rupees.
            - Use only what the product list says. Never invent products, prices, sizes, certifications or health claims.
            - If none of the listed products fits the question, say that you could not find a matching product.
            - Answer in 1 to 4 short sentences.
            Products:
            """;

    private final ChatClient chatClient;
    private final CatalogIndex catalogIndex;
    private final int topK;
    private final double similarityThreshold;

    public ShoppingAssistant(ChatClient.Builder chatClientBuilder, CatalogIndex catalogIndex,
                             @Value("${assistant.top-k:4}") int topK,
                             @Value("${assistant.similarity-threshold:0.5}") double similarityThreshold) {
        this.chatClient = chatClientBuilder.build();
        this.catalogIndex = catalogIndex;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    public AssistantAnswerDto answer(String question) {
        try {
            List<Document> products = catalogIndex.search(question, topK, similarityThreshold);
            if (products.isEmpty()) {
                return new AssistantAnswerDto(NOTHING_FOUND, List.of());
            }
            // A Prompt of plain messages: the product texts are not run through a template, so a "{" in a
            // description cannot break it
            String productList = products.stream().map(ShoppingAssistant::productText).collect(Collectors.joining("\n"));
            String answer = chatClient.prompt(new Prompt(List.of(
                            new SystemMessage(INSTRUCTIONS + productList), new UserMessage(question))))
                    .call()
                    .content();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("The model gave an empty answer");
            }
            return new AssistantAnswerDto(answer.strip(), products.stream().map(AssistantAnswerDto.ProductRef::from).toList());
        } catch (RuntimeException e) {
            // The embedding model or the language model is down, too slow, or gave nothing usable
            log.warn("The shopping assistant failed: {}", e.toString());
            throw new AssistantUnavailableException("The shopping assistant is not available right now; please try again shortly", e);
        }
    }

    private static String productText(Document product) {
        String text = product.getText() == null ? "" : product.getText();
        return "- " + (text.startsWith(CatalogIndex.DOCUMENT_PREFIX) ? text.substring(CatalogIndex.DOCUMENT_PREFIX.length()) : text);
    }
}
