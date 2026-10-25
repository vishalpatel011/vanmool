package com.vanmool.productservice.models;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Category extends Base{
    // LAZY (the default for collections, written out to make it explicit): a category can hold many products,
    // and nothing loads them through this list. Product counts come from a query in CategoryRepository.
    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    private List<Product> products;

}