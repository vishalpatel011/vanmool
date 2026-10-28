package com.vanmool.productservice.assistant;

import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.repositories.ProductRepository;
import com.vanmool.productservice.services.ProductChangedEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// The catalog as vectors, for the shopping assistant to find products by meaning rather than by words: each product
// becomes one document, plain sentences with its name, category, origin, materials, price, description and story,
// turned into a vector by the embedding model. Labels such as "Category:" in every document made all products look
// alike to the model, so the documents have none.
//
// Search is hybrid: an embedding model knows rare names such as "Dangs" poorly (measured: the only product from Dangs
// ranked last for "products from Dangs"), so a product whose text contains words of the question gains up to
// KEYWORD_BOOST on top of its similarity. The vectors are kept in memory (Spring AI's SimpleVectorStore): built from MySQL when the
// service is ready, or at the first question if the embedding model was not reachable then, and kept current after
// every product change is committed. A rolled-back change never reaches the index.
@Slf4j
@Component
public class CatalogIndex {
    // nomic-embed-text was trained with these prefixes, which tell a stored text from a question about it
    static final String DOCUMENT_PREFIX = "search_document: ";
    static final String QUERY_PREFIX = "search_query: ";
    static final double KEYWORD_BOOST = 0.15;
    private static final Set<String> FILLER_WORDS = Set.of("which", "what", "where", "from", "with", "have", "that",
            "this", "your", "yours", "product", "products", "something", "things", "made", "make", "does", "there",
            "want", "need", "sell", "some", "show", "find", "looking", "please", "about", "under", "below", "rupees");

    private final EmbeddingModel embeddingModel;
    private final ProductRepository productRepository;
    private final boolean indexOnStartup;
    private volatile SimpleVectorStore store;

    public CatalogIndex(EmbeddingModel embeddingModel, ProductRepository productRepository,
                        @Value("${assistant.index-on-startup:true}") boolean indexOnStartup) {
        this.embeddingModel = embeddingModel;
        this.productRepository = productRepository;
        this.indexOnStartup = indexOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void buildWhenReady() {
        if (!indexOnStartup) {
            return;
        }
        try {
            ensureBuilt();
        } catch (RuntimeException e) {
            // Ollama not running: the service works without it, and the first question tries again
            log.warn("The catalog index could not be built at startup: {}", e.toString());
        }
    }

    public synchronized void ensureBuilt() {
        if (store != null) {
            return;
        }
        List<Product> products = productRepository.findAllByDeletedFalse();
        SimpleVectorStore newStore = SimpleVectorStore.builder(embeddingModel).build();
        if (!products.isEmpty()) {
            newStore.add(products.stream().map(CatalogIndex::document).toList());
        }
        store = newStore;
        log.info("Catalog index built with {} products", products.size());
    }

    // The products closest to the question, most similar first: similarity of meaning (cosine, 0 to 1), plus up to
    // KEYWORD_BOOST for the share of the question's words found in the product's text
    public List<Document> search(String question, int topK, double similarityThreshold) {
        ensureBuilt();
        List<Document> candidates = store.similaritySearch(SearchRequest.builder()
                .query(QUERY_PREFIX + question)
                .topK(topK * 3)
                .similarityThreshold(Math.max(0, similarityThreshold - KEYWORD_BOOST))
                .build());
        return rank(candidates, question, topK, similarityThreshold);
    }

    // Ranks the candidates by their similarity plus their keyword boost, keeps those at least at the threshold
    static List<Document> rank(List<Document> candidates, String question, int topK, double similarityThreshold) {
        List<String> words = Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
                .filter(word -> word.length() >= 4 && !FILLER_WORDS.contains(word))
                .toList();
        return candidates.stream()
                .map(document -> new Scored(document, score(document, words)))
                .filter(scored -> scored.score() >= similarityThreshold)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::document)
                .toList();
    }

    private record Scored(Document document, double score) {
    }

    private static double score(Document document, List<String> words) {
        double similarity = document.getScore() == null ? 0 : document.getScore();
        if (words.isEmpty()) {
            return similarity;
        }
        String text = document.getText() == null ? "" : document.getText().toLowerCase(Locale.ROOT);
        long found = words.stream().filter(text::contains).count();
        return similarity + KEYWORD_BOOST * found / words.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public synchronized void onProductChanged(ProductChangedEvent event) {
        if (store == null) {
            return; // not built yet: the whole catalog is read when it is
        }
        try {
            store.delete(List.of(event.productId().toString()));
            productRepository.findByIdAndDeletedFalse(event.productId())
                    .ifPresent(product -> store.add(List.of(document(product))));
        } catch (RuntimeException e) {
            // The embedding model is not reachable: start again from MySQL at the next question
            log.warn("The catalog index could not be updated for product {}: {}", event.productId(), e.toString());
            store = null;
        }
    }

    // For example "Handcrafted Bamboo Basket: Traditional Crafts from Dangs, Gujarat, made of bamboo. Price ₹499.99. A
    // round basket ... Woven by hand ..."
    static Document document(Product product) {
        String category = product.getCategory() == null ? null : product.getCategory().getName();
        StringBuilder text = new StringBuilder(DOCUMENT_PREFIX).append(product.getName()).append(':');
        text.append(' ').append(isBlank(category) ? "a product" : category.strip());
        if (!isBlank(product.getOriginRegion())) {
            text.append(" from ").append(product.getOriginRegion().strip());
        }
        if (!isBlank(product.getMaterials())) {
            text.append(", made of ").append(product.getMaterials().strip().toLowerCase(Locale.ROOT));
        }
        text.append('.');
        if (product.getPrice() != null) {
            text.append(" Price ₹").append(product.getPrice().toPlainString()).append('.');
        }
        append(text, product.getDescription());
        append(text, product.getStory());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", product.getName());
        metadata.put("price", product.getPrice() == null ? "" : product.getPrice().toPlainString());
        metadata.put("category", Objects.requireNonNullElse(category, ""));
        metadata.put("originRegion", Objects.requireNonNullElse(product.getOriginRegion(), ""));
        return Document.builder().id(product.getId().toString()).text(text.toString()).metadata(metadata).build();
    }

    private static void append(StringBuilder text, String sentence) {
        if (!isBlank(sentence)) {
            String strip = sentence.strip();
            text.append(' ').append(strip).append(strip.endsWith(".") ? "" : ".");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
