package com.vanmool.productservice.services;

import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.models.Category;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.repositories.CategoryRepository;
import com.vanmool.productservice.repositories.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service("productDbService")
@Primary
public class ProductDbService implements ProductService{
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public ProductDbService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Override
    public Product getProductById(long id) throws ProductNotFoundException {
        Optional<Product> productOptional = productRepository.findById(id);
        if(productOptional.isEmpty()) {
            throw new ProductNotFoundException("Product with id " + id + " not found");
        }

        return productOptional.get() ;
    }

    @Override
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Override
    public Product createProduct(String name, String description, double price,  String imageUrl, String category) {
        Product product = new Product();
        buildProduct(product,name, description, price, imageUrl, category);

        return productRepository.save(product);
    }

    @Override
    public Product replaceProduct(long id, String name, String description, double price, String imageUrl, String category) {
        Product product = new Product();
        product.setId(id);
        buildProduct(product,name, description, price, imageUrl, category);

        return productRepository.save(product);
    }

    @Override
    public Product patchProduct(long id, JsonPatch patch) throws ProductNotFoundException, JsonPatchException, JsonProcessingException {
        return null;
    }

    private Category getCategoryFromDB(String categoryName) {
        Optional<Category> categoryOptional = categoryRepository.findByName(categoryName);
        if(categoryOptional.isPresent()){
            return categoryOptional.get();
        }

        Category newCategory = new Category();
        newCategory.setName(categoryName);
        return categoryRepository.save(newCategory);
    }

    private void buildProduct(Product product,String name,String description ,double price,  String imageUrl, String category) {
        // This method should contain logic to build a product object
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setImageUrl(imageUrl);

        Category categoryObj = getCategoryFromDB(category);
        product.setCategory(categoryObj);
    }
}
