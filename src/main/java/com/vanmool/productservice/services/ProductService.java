package com.vanmool.productservice.services;

import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;

import java.util.List;

public interface ProductService {
    Product getProductById(long id) throws ProductNotFoundException;
    List<Product> getAllProducts();
    Product createProduct(String name, String description, double price,String imageUrl, String category);
    Product replaceProduct(long id, String name, String description, double price, String imageUrl, String category);
    Product patchProduct(long id, JsonPatch patch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException;
}

