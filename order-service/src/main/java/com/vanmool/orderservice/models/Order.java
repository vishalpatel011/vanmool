package com.vanmool.orderservice.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orders")
public class Order {
    // Chosen by OrderService before the order is saved, because the stock is reserved under this id first
    @Id
    private UUID id;
    // user-auth-service's id of the customer: an external reference, not a foreign key
    @Column(nullable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;
    @Column(length = 100)
    private String idempotencyKey;
    private String customerEmail;
    @Embedded
    private ShippingAddress shippingAddress;
    // The items belong to the order: saved and deleted with it (cascade), never shared.
    // LAZY. A single order is read with its items in one query (@EntityGraph in OrderRepository). For a page of order
    // history, @BatchSize loads the items of all orders on the page in one more query, instead of one query per order
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<OrderItem> items = new ArrayList<>();
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant lastModified;
    // Also tells Spring Data that an order with an id but no version yet is new, so save() inserts it directly
    @Version
    private Long version;

    public void addItem(UUID productId, String productName, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrder(this);
        item.setProductId(productId);
        item.setProductName(productName);
        item.setUnitPrice(unitPrice);
        item.setQuantity(quantity);
        items.add(item);
    }

    public BigDecimal calculateTotal() {
        return items.stream().map(OrderItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
