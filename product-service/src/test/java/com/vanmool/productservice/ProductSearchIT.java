package com.vanmool.productservice;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductSearchCriteria;
import com.vanmool.productservice.services.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Integration test of the catalog query (ProductRepository.search) against MySQL: the LIKE patterns, collation,
// optional filters, sorting, paging and the count query only really run in the database. The test makes its own
// products in a category with a unique name, so it never mixes with other data, and removes them afterwards.
@SpringBootTest
class ProductSearchIT {
    private final String category = "it-search-" + UUID.randomUUID();

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void create(String name, String price, String originRegion, String materials, String description) {
        ProductRequestDto request = new ProductRequestDto();
        request.setName(name);
        request.setPrice(new BigDecimal(price));
        request.setOriginRegion(originRegion);
        request.setMaterials(materials);
        request.setDescription(description);
        request.setCategory(category);
        productService.createProduct(request);
    }

    @BeforeEach
    void createProducts() throws Exception {
        create("Bamboo Basket", "450.00", "Dangs, Gujarat", "Bamboo", "A round basket");
        create("Fruit Tray", "350.00", "Dangs, Gujarat", "Split bamboo", "A flat tray");
        create("Spinning Top", "180.00", "Sankheda, Gujarat", "Teak wood", "100% hand-turned");
        create("Bamboo Lamp", "200.00", "Dangs, Gujarat", "Bamboo", "Removed from the catalog");
        Product lamp = search(new ProductSearchCriteria("Lamp", category, null, null, null), PageRequest.of(0, 10)).getContent().get(0);
        productService.deleteProduct(lamp.getId());
    }

    @AfterEach
    void removeProducts() {
        jdbcTemplate.update("DELETE p FROM product p JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE FROM category WHERE name = ?", category);
    }

    private Page<Product> search(ProductSearchCriteria criteria, PageRequest pageRequest) {
        return productService.searchProducts(criteria, pageRequest);
    }

    private List<String> names(ProductSearchCriteria criteria, Sort sort) {
        return search(criteria, PageRequest.of(0, 10, sort)).getContent().stream().map(Product::getName).toList();
    }

    @Test
    void text_isFoundInNameMaterialsOriginAndDescription_inAnyLetterCase_andNeverInDeletedProducts() {
        Sort byName = Sort.by("name");
        // "bamboo": the name of the basket, the materials of the tray; the deleted lamp is left out
        assertEquals(List.of("Bamboo Basket", "Fruit Tray"), names(new ProductSearchCriteria("BAMBOO", category, null, null, null), byName));
        // the origin region
        assertEquals(List.of("Spinning Top"), names(new ProductSearchCriteria("sankheda", category, null, null, null), byName));
        // the description
        assertEquals(List.of("Spinning Top"), names(new ProductSearchCriteria("hand-turned", category, null, null, null), byName));
    }

    @Test
    void percentSign_isSearchedForLiterally() {
        // Only the top's description contains "%"; unescaped, "%" would match every product
        assertEquals(List.of("Spinning Top"), names(new ProductSearchCriteria("%", category, null, null, null), Sort.by("name")));
        assertEquals(List.of(), names(new ProductSearchCriteria("_", category, null, null, null), Sort.by("name")));
    }

    @Test
    void filters_combine() {
        Sort byPrice = Sort.by("price");
        assertEquals(List.of("Fruit Tray", "Bamboo Basket"),
                names(new ProductSearchCriteria(null, category, "dangs", null, null), byPrice));
        assertEquals(List.of("Spinning Top", "Fruit Tray"),
                names(new ProductSearchCriteria(null, category, null, new BigDecimal("150"), new BigDecimal("400")), byPrice));
        assertEquals(List.of("Fruit Tray"),
                names(new ProductSearchCriteria("bamboo", category, "Dangs", new BigDecimal("300"), new BigDecimal("400")), byPrice));
    }

    @Test
    void pages_followTheSortOrderAndCountEveryMatch() {
        // Act
        ProductSearchCriteria all = new ProductSearchCriteria(null, category, null, null, null);
        Page<Product> first = search(all, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "price")));
        Page<Product> second = search(all, PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "price")));

        // Assert: 3 products in the catalog (the deleted one is not counted), 2 per page
        assertEquals(List.of("Bamboo Basket", "Fruit Tray"), first.getContent().stream().map(Product::getName).toList());
        assertEquals(List.of("Spinning Top"), second.getContent().stream().map(Product::getName).toList());
        assertEquals(3, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
    }

    @Test
    void eachProduct_comesWithItsCategory() {
        // The category is fetched in the same query (entity graph), so reading it here needs no open session
        Product product = search(new ProductSearchCriteria("Basket", category, null, null, null), PageRequest.of(0, 10)).getContent().get(0);
        assertEquals(category, product.getCategory().getName());
    }
}
