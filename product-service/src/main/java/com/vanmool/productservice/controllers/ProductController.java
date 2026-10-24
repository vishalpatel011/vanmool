package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.CreateFakeStoreProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
public class ProductController {
    ProductService productService;

    public ProductController(@Qualifier("productDbService") ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public List<ProductResponseDto> getAllProducts(){
        List<Product> products = productService.getAllProducts();
        //List<ProductResponseDto> productResponseDtos = products.stream().map(ProductResponseDto::from).toList();
        List<ProductResponseDto> productResponseDtos = new ArrayList<>();
        for(Product product : products){
            productResponseDtos.add(ProductResponseDto.from(product));
        }
        return productResponseDtos;
    }

    @GetMapping("/products/{id}")
    public ProductResponseDto getProductbyId(@PathVariable("id") long id) throws ProductNotFoundException {
        Product product = productService.getProductById(id);
        return ProductResponseDto.from(product);
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponseDto> createProduct(@RequestBody CreateFakeStoreProductRequestDto createFakeStoreProductRequestDto) {
        Product product = productService.createProduct(createFakeStoreProductRequestDto.getName(),
                                                        createFakeStoreProductRequestDto.getDescription(),
                                                        createFakeStoreProductRequestDto.getPrice(),
                                                        createFakeStoreProductRequestDto.getImageUrl(),
                                                        createFakeStoreProductRequestDto.getCategory());
        return new ResponseEntity<>(ProductResponseDto.from(product), HttpStatus.CREATED);
    }

    @PutMapping("/products/{id}")
    public ProductResponseDto replaceProduct(@PathVariable("id") long id, @RequestBody CreateFakeStoreProductRequestDto createFakeStoreProductRequestDto) throws ProductNotFoundException {
        Product product = productService.replaceProduct(
                id,
                createFakeStoreProductRequestDto.getName(),
                createFakeStoreProductRequestDto.getDescription(), createFakeStoreProductRequestDto.getPrice(),
                createFakeStoreProductRequestDto.getImageUrl(),
                createFakeStoreProductRequestDto.getCategory());
        return ProductResponseDto.from(product);
    }

    @PatchMapping(value = "/products/{id}", consumes = "application/json-patch+json")
    public ProductResponseDto patchProduct(@PathVariable("id") long id, @RequestBody JsonPatch jsonPatch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException {
        Product product = productService.patchProduct(id, jsonPatch);
        return ProductResponseDto.from(product);
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable("id") long id) throws ProductNotFoundException {
        productService.deleteProduct(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

}

