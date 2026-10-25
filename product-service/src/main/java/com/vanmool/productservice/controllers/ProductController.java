package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/products")
    public List<ProductResponseDto> getAllProducts(@RequestParam(value = "category", required = false) String category){
        // GET /products?category=Natural Foods lists one category; without it (or left empty), every product
        List<Product> products = (category == null || category.isBlank())
                ? productService.getAllProducts()
                : productService.getProductsByCategory(category);
        //List<ProductResponseDto> productResponseDtos = products.stream().map(ProductResponseDto::from).toList();
        List<ProductResponseDto> productResponseDtos = new ArrayList<>();
        for(Product product : products){
            productResponseDtos.add(ProductResponseDto.from(product));
        }
        return productResponseDtos;
    }

    @GetMapping("/products/{id}")
    public ProductResponseDto getProductById(@PathVariable("id") UUID id) throws ProductNotFoundException {
        Product product = productService.getProductById(id);
        return ProductResponseDto.from(product);
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponseDto> createProduct(@Valid @RequestBody ProductRequestDto productRequestDto,
                                                            UriComponentsBuilder uriComponentsBuilder) {
        Product product = productService.createProduct(productRequestDto);
        // 201 Created says where the new product lives: Location: http://host/products/{id}.
        // Spring fills the builder with the scheme, host and port of the current request.
        URI location = uriComponentsBuilder.path("/products/{id}").buildAndExpand(product.getId()).toUri();
        return ResponseEntity.created(location).body(ProductResponseDto.from(product));
    }

    @PutMapping("/products/{id}")
    public ProductResponseDto replaceProduct(@PathVariable("id") UUID id, @Valid @RequestBody ProductRequestDto productRequestDto) throws ProductNotFoundException {
        Product product = productService.replaceProduct(id, productRequestDto);
        return ProductResponseDto.from(product);
    }

    @PatchMapping(value = "/products/{id}", consumes = "application/json-patch+json")
    public ProductResponseDto patchProduct(@PathVariable("id") UUID id, @RequestBody JsonPatch jsonPatch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException {
        Product product = productService.patchProduct(id, jsonPatch);
        return ProductResponseDto.from(product);
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable("id") UUID id) throws ProductNotFoundException {
        productService.deleteProduct(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

}

