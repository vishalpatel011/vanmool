package com.vanmool.productservice.services;

import com.vanmool.productservice.dtos.CreateFakeStoreProductRequestDto;
import com.vanmool.productservice.dtos.FakeStoreRequestDto;
import com.vanmool.productservice.dtos.FakeStoreResponseDto;
import com.vanmool.productservice.dtos.ProductResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service("fakeStoreProductService")
public class FakeStoreProductService implements ProductService{
    RestTemplate restTemplate;

    public FakeStoreProductService(RestTemplate restTemplate) {

        this.restTemplate = restTemplate;
    }

    @Override
    public Product getProductById(long id) throws ProductNotFoundException {
        FakeStoreResponseDto fakeStoreResponseDto =
                restTemplate.getForObject("https://fakestoreapi.com/products/" + id, FakeStoreResponseDto.class);

        if(fakeStoreResponseDto == null) {
            throw new ProductNotFoundException("Product not found with id: " + id);
        }
        return fakeStoreResponseDto.toProduct();
    }

    @Override
    public List<Product> getAllProducts() {
        FakeStoreResponseDto[] fakeStoreResponseDtos =
                restTemplate.getForObject("https://fakestoreapi.com/products", FakeStoreResponseDto[].class);

        List<Product> products = new ArrayList<>();
        for(FakeStoreResponseDto fakeStoreResponseDto : fakeStoreResponseDtos) {
            products.add(fakeStoreResponseDto.toProduct());
        }
        return products;
    }

    @Override
    public Product createProduct(String name, String description, double price,  String imageUrl, String category) {
        FakeStoreRequestDto fakeStoreProductRequestDto = createDtoFromParam(name, price, description, imageUrl, category);
        FakeStoreResponseDto fakeStoreResponseDto = restTemplate.postForObject("https://fakestoreapi.com/products", fakeStoreProductRequestDto, FakeStoreResponseDto.class, String.class);
        return fakeStoreResponseDto.toProduct();
    }

    @Override
    public Product replaceProduct(long id, String name, String description, double price, String imageUrl, String category) {
        FakeStoreRequestDto updatedfakeStoreRequestDto = createDtoFromParam(name, price, description, imageUrl, category);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<FakeStoreRequestDto> requestEntity = new HttpEntity<>(updatedfakeStoreRequestDto, headers);
        ResponseEntity<FakeStoreResponseDto>  responseEntity = restTemplate.exchange("https://fakestoreapi.com/products/" + id,
                HttpMethod.PUT, requestEntity, FakeStoreResponseDto.class);
        return responseEntity.getBody().toProduct();
    }

    @Override
    public Product patchProduct(long id, JsonPatch patch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException {

        // Get existing product
        Product existingProduct = getProductById(id);

        // Convert Product to the same JSON that GET returns (the DTO)
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode productNode = objectMapper.valueToTree(ProductResponseDto.from(existingProduct));

        // Apply Patch
        JsonNode patchedNode = patch.apply(productNode);

        //Convert back to the DTO
        ProductResponseDto patchedProduct = objectMapper.treeToValue(patchedNode, ProductResponseDto.class);

        return replaceProduct(id,
                patchedProduct.getName(),
                patchedProduct.getDescription(),
                patchedProduct.getPrice(),
                patchedProduct.getImageUrl(),
                patchedProduct.getCategory()
        );
    }


    private FakeStoreRequestDto createDtoFromParam(String name, double price, String description, String imageUrl, String category) {
        FakeStoreRequestDto fakeStoreProductRequestDto = new FakeStoreRequestDto();
        fakeStoreProductRequestDto.setTitle(name);
        fakeStoreProductRequestDto.setPrice(price);
        fakeStoreProductRequestDto.setDescription(description);
        fakeStoreProductRequestDto.setImage(imageUrl);
        fakeStoreProductRequestDto.setCategory(category);
        return fakeStoreProductRequestDto;

    }

}

