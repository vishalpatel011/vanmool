package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.services.ProductSearchCriteria;
import com.vanmool.productservice.services.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

@RestController
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // The catalog: search, filters, paging and sorting, all optional query parameters, for example
    // GET /products?q=bamboo&category=Traditional Crafts&minPrice=100&maxPrice=600&page=0&size=20&sort=price,asc
    // Without parameters: the first 20 products, newest first. A page is never larger than 100 (application.properties).
    @GetMapping("/products")
    public Page<ProductResponseDto> getProducts(@RequestParam(value = "q", required = false) String text,
                                                @RequestParam(value = "category", required = false) String category,
                                                @RequestParam(value = "origin", required = false) String origin,
                                                @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
                                                @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice,
                                                @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                                Pageable pageable){
        ProductSearchCriteria criteria = new ProductSearchCriteria(text, category, origin, minPrice, maxPrice);
        return productService.searchProducts(criteria, pageable).map(ProductResponseDto::from);
    }

    @GetMapping("/products/{id}")
    public ProductResponseDto getProductById(@PathVariable("id") UUID id) throws ProductNotFoundException {
        // Already a DTO: the service caches this response in Redis
        return productService.getProductById(id);
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

