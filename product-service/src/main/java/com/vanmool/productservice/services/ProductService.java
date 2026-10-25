package com.vanmool.productservice.services;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ProductService {
    ProductResponseDto getProductById(UUID id) throws ProductNotFoundException;
    Page<Product> searchProducts(ProductSearchCriteria criteria, Pageable pageable);
    Product createProduct(ProductRequestDto productRequestDto);
    Product replaceProduct(UUID id, ProductRequestDto productRequestDto) throws ProductNotFoundException;
    Product patchProduct(UUID id, JsonPatch patch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException;
    void deleteProduct(UUID id) throws ProductNotFoundException;
}

