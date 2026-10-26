package com.vanmool.orderservice.services;

import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatusChange;
import com.vanmool.orderservice.repositories.OrderStatusChangeRepository;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Builds the order responses with each order's timeline: one query for one order, one query for a whole page
// (never one per order)
@Component
public class OrderResponses {
    private final OrderStatusChangeRepository statusChangeRepository;

    public OrderResponses(OrderStatusChangeRepository statusChangeRepository) {
        this.statusChangeRepository = statusChangeRepository;
    }

    public OrderResponseDto of(Order order) {
        return OrderResponseDto.from(order, statusChangeRepository.findByOrderIdOrderByChangedAtAsc(order.getId()));
    }

    // Reads the orders' items too: call it inside a transaction, so they are batch fetched
    public Page<OrderResponseDto> of(Page<Order> orders) {
        List<UUID> ids = orders.getContent().stream().map(Order::getId).toList();
        Map<UUID, List<OrderStatusChange>> history = ids.isEmpty() ? Map.of()
                : statusChangeRepository.findByOrderIdInOrderByChangedAtAsc(ids).stream()
                .collect(Collectors.groupingBy(OrderStatusChange::getOrderId));
        return orders.map(order -> OrderResponseDto.from(order, history.getOrDefault(order.getId(), List.of())));
    }
}
