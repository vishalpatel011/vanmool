package com.vanmool.productservice.services;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;

import java.util.List;
import java.util.UUID;

public interface ProductService {
    Product getProductById(UUID id) throws ProductNotFoundException;
    List<Product> getAllProducts();
    List<Product> getProductsByCategory(String categoryName);
    Product createProduct(ProductRequestDto productRequestDto);
    Product replaceProduct(UUID id, ProductRequestDto productRequestDto) throws ProductNotFoundException;
    Product patchProduct(UUID id, JsonPatch patch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException;
    void deleteProduct(UUID id) throws ProductNotFoundException;
}

