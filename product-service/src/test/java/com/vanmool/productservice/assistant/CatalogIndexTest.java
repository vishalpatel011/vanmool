package com.vanmool.productservice.assistant;

import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.repositories.ProductRepository;
import com.vanmool.productservice.services.ProductChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogIndexTest {
    // Stands in for the embedding model: one dimension per group of words, so "basket" and "bamboo" are close and
    // "biscuits" is far from both. Can be told to fail, like an Ollama that is not running
    private static final class KeywordEmbeddingModel implements EmbeddingModel {
        private static final List<List<String>> GROUPS = List.of(List.of("bamboo", "basket"),
                List.of("ragi", "biscuit", "millet"), List.of("wood", "spinning", "toy"), List.of("dangs", "ahwa"));
        private boolean down;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (down) {
                throw new ResourceAccessException("Connection refused: localhost:11434");
            }
            List<Embedding> embeddings = new ArrayList<>();
            for (String text : request.getInstructions()) {
                float[] vector = new float[GROUPS.size() + 1];
                for (int i = 0; i < GROUPS.size(); i++) {
                    vector[i] = GROUPS.get(i).stream().anyMatch(text.toLowerCase()::contains) ? 1f : 0f;
                }
                vector[GROUPS.size()] = 0.1f;
                embeddings.add(new Embedding(vector, embeddings.size()));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
        }
    }

    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final UUID BISCUITS_ID = UUID.fromString("b0b9c082-e656-4189-bc3f-468000368e50");

    private final KeywordEmbeddingModel embeddingModel = new KeywordEmbeddingModel();
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final CatalogIndex index = new CatalogIndex(embeddingModel, productRepository, true);

    private static Product product(UUID id, String name, String price, String category, String origin, String materials) {
        Category productCategory = new Category();
        productCategory.setName(category);
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setCategory(productCategory);
        product.setOriginRegion(origin);
        product.setMaterials(materials);
        return product;
    }

    private final Product basket = product(BASKET_ID, "Handcrafted Bamboo Basket", "499.99", "Traditional Crafts", "Dangs, Gujarat", "Bamboo");
    private final Product biscuits = product(BISCUITS_ID, "Ragi Biscuits", "120.00", "Natural Foods", "Gujarat", "Ragi flour, jaggery");

    @Test
    void theCatalog_isFoundByMeaning_closestFirst_andAnUnrelatedProductIsLeftOut() {
        // Arrange
        when(productRepository.findAllByDeletedFalse()).thenReturn(List.of(basket, biscuits));

        // Act
        List<Document> found = index.search("a basket made of bamboo", 4, 0.5);

        // Assert: the basket, with its facts in the text and as metadata; the biscuits are not similar enough
        assertEquals(List.of(BASKET_ID.toString()), found.stream().map(Document::getId).toList());
        Document document = found.get(0);
        assertTrue(document.getText().startsWith(CatalogIndex.DOCUMENT_PREFIX
                + "Handcrafted Bamboo Basket: Traditional Crafts from Dangs, Gujarat, made of bamboo. Price ₹499.99."), document.getText());
        assertEquals("Traditional Crafts", document.getMetadata().get("category"));
    }

    private static Document candidate(String text, double similarity) {
        return Document.builder().text(CatalogIndex.DOCUMENT_PREFIX + text).score(similarity).build();
    }

    @Test
    void aNameFromTheQuestion_foundInAProduct_outranksAProductSlightlyCloserInMeaning() {
        // Arrange: the tray is a little closer in meaning, but only the mat comes from Kaprada, a name an embedding
        // model knows little about
        Document tray = candidate("Bamboo Tray: Traditional Crafts from Gujarat, made of bamboo.", 0.62);
        Document mat = candidate("Bamboo Mat: Traditional Crafts from Kaprada, Gujarat, made of bamboo.", 0.58);

        // Act: "bamboo" and "kaprada" count, "from" is a filler word
        List<Document> ranked = CatalogIndex.rank(List.of(tray, mat), "bamboo from Kaprada", 4, 0.5);

        // Assert: mat 0.58 + 0.15 (both words) is ahead of tray 0.62 + 0.075 (one word)
        assertEquals(List.of(mat, tray), ranked);
    }

    @Test
    void theThreshold_appliesToTheBoostedScore() {
        // A weak match that has every word of the question is kept; one without them is dropped
        Document withTheWords = candidate("Bamboo Mat from Kaprada, Gujarat.", 0.40);
        Document without = candidate("Ragi Biscuits from Gujarat.", 0.45);

        assertEquals(List.of(withTheWords), CatalogIndex.rank(List.of(withTheWords, without), "bamboo Kaprada", 4, 0.5));
    }

    @Test
    void aChangedProduct_isIndexedAgain_andADeletedOne_disappears() {
        // Arrange
        when(productRepository.findAllByDeletedFalse()).thenReturn(List.of(basket, biscuits));
        index.ensureBuilt();

        // Act: the biscuits become a wooden toy; the basket is deleted
        biscuits.setName("Wooden Spinning Top");
        when(productRepository.findByIdAndDeletedFalse(BISCUITS_ID)).thenReturn(Optional.of(biscuits));
        index.onProductChanged(new ProductChangedEvent(BISCUITS_ID));
        when(productRepository.findByIdAndDeletedFalse(BASKET_ID)).thenReturn(Optional.empty());
        index.onProductChanged(new ProductChangedEvent(BASKET_ID));

        // Assert
        assertEquals(List.of(BISCUITS_ID.toString()), index.search("a wooden toy", 4, 0.5).stream().map(Document::getId).toList());
        assertTrue(index.search("a bamboo basket", 4, 0.5).isEmpty());
    }

    @Test
    void changesBeforeTheIndexIsBuilt_areIgnored_becauseBuildingReadsTheWholeCatalog() {
        index.onProductChanged(new ProductChangedEvent(BASKET_ID));

        verifyNoInteractions(productRepository);
    }

    @Test
    void anEmbeddingModelThatIsDown_failsTheSearch_andTheNextSearchBuildsTheIndex() {
        // Arrange
        when(productRepository.findAllByDeletedFalse()).thenReturn(List.of(basket, biscuits));
        embeddingModel.down = true;

        // Act & Assert: nothing half-built is kept
        assertThrows(ResourceAccessException.class, () -> index.search("bamboo basket", 4, 0.5));
        embeddingModel.down = false;
        assertEquals(1, index.search("bamboo basket", 4, 0.5).size());
    }
}
