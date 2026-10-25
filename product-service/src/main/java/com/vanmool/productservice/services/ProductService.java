package com.vanmool.productservice.services;

import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProductService {
    Product getProductById(UUID id) throws ProductNotFoundException;
    List<Product> getAllProducts();
    List<Product> getProductsByCategory(String categoryName);
    Product createProduct(String name, String description, BigDecimal price,String imageUrl, String category);
    Product replaceProduct(UUID id, String name, String description, BigDecimal price, String imageUrl, String category) throws ProductNotFoundException;
    Product patchProduct(UUID id, JsonPatch patch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException;
    void deleteProduct(UUID id) throws ProductNotFoundException;
}

