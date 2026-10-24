package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.ProductRequestDto;
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
    private final ProductService productService;

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
    public ProductResponseDto getProductById(@PathVariable("id") long id) throws ProductNotFoundException {
        Product product = productService.getProductById(id);
        return ProductResponseDto.from(product);
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponseDto> createProduct(@RequestBody ProductRequestDto productRequestDto) {
        Product product = productService.createProduct(productRequestDto.getName(),
                                                        productRequestDto.getDescription(),
                                                        productRequestDto.getPrice(),
                                                        productRequestDto.getImageUrl(),
                                                        productRequestDto.getCategory());
        return new ResponseEntity<>(ProductResponseDto.from(product), HttpStatus.CREATED);
    }

    @PutMapping("/products/{id}")
    public ProductResponseDto replaceProduct(@PathVariable("id") long id, @RequestBody ProductRequestDto productRequestDto) throws ProductNotFoundException {
        Product product = productService.replaceProduct(
                id,
                productRequestDto.getName(),
                productRequestDto.getDescription(), productRequestDto.getPrice(),
                productRequestDto.getImageUrl(),
                productRequestDto.getCategory());
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

