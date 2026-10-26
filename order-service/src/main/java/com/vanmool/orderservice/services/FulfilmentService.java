package com.vanmool.orderservice.services;

import com.vanmool.orderservice.configs.KafkaConfig;
import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.events.OrderDeliveredEvent;
import com.vanmool.orderservice.events.OrderShippedEvent;
import com.vanmool.orderservice.events.OutboxWriter;
import com.vanmool.orderservice.exceptions.InvalidOrderStateException;
import com.vanmool.orderservice.exceptions.OrderNotFoundException;
import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.models.OrderStatusChange;
import com.vanmool.orderservice.repositories.OrderRepository;
import com.vanmool.orderservice.repositories.OrderStatusChangeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Sending the paid orders (PRD 4.3). Producers hand their products to Vanmool's operations team, who pack and send
// each order and mark it SHIPPED, then DELIVERED when the carrier reports it. One order can hold products of several
// producers, so it is shipped as a whole by the team (ADMIN), not by a producer.
// Each change is one conditional UPDATE plus its timeline row and its event (outbox), in one transaction, as in
// OrderService. Repeating a request (a retry after a timeout) changes nothing and publishes nothing.
@Slf4j
@Service
public class FulfilmentService {
    private final OrderRepository orderRepository;
    private final OrderStatusChangeRepository statusChangeRepository;
    private final OrderResponses orderResponses;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public FulfilmentService(OrderRepository orderRepository, OrderStatusChangeRepository statusChangeRepository,
                             OrderResponses orderResponses, OutboxWriter outboxWriter,
                             TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.statusChangeRepository = statusChangeRepository;
        this.orderResponses = orderResponses;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    // The orders in one status, oldest first: for CONFIRMED, the queue of paid orders waiting to be sent, in the
    // order they were placed. The id breaks ties, so pages never overlap
    @Transactional(readOnly = true)
    public Page<OrderResponseDto> getOrders(OrderStatus status, Pageable pageable) {
        Pageable oldestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by("createdAt").and(Sort.by("id")));
        return orderResponses.of(orderRepository.findByStatus(status, oldestFirst));
    }

    public OrderResponseDto ship(String adminId, UUID orderId, String carrier, String trackingNumber) {
        Order order = findOrder(orderId);
        String cleanCarrier = carrier.strip();
        if (order.getStatus() == OrderStatus.SHIPPED) {
            if (cleanCarrier.equals(order.getCarrier()) && Objects.equals(trackingNumber, order.getTrackingNumber())) {
                return orderResponses.of(order);
            }
            throw new InvalidOrderStateException("The order was already shipped: " + shipment(order.getCarrier(),
                    order.getTrackingNumber()));
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("An order that is " + order.getStatus() + " cannot be shipped");
        }
        Instant now = clock.instant();
        transactionTemplate.executeWithoutResult(status -> {
            if (orderRepository.ship(orderId, cleanCarrier, trackingNumber) == 0) {
                throw new InvalidOrderStateException("The order changed while it was being shipped; check its status");
            }
            statusChangeRepository.save(OrderStatusChange.of(orderId, OrderStatus.SHIPPED, now,
                    shipment(cleanCarrier, trackingNumber)));
            outboxWriter.add(KafkaConfig.ORDER_EVENTS_TOPIC, orderId.toString(),
                    OrderShippedEvent.of(order, cleanCarrier, trackingNumber, now));
        });
        log.info("Order {} SHIPPED by {} ({})", orderId, adminId, shipment(cleanCarrier, trackingNumber));
        return orderResponses.of(findOrder(orderId));
    }

    public OrderResponseDto markDelivered(String adminId, UUID orderId) {
        Order order = findOrder(orderId);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            return orderResponses.of(order);
        }
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new InvalidOrderStateException("An order that is " + order.getStatus() + " cannot be marked delivered");
        }
        Instant now = clock.instant();
        transactionTemplate.executeWithoutResult(status -> {
            if (orderRepository.moveStatus(orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED) == 0) {
                throw new InvalidOrderStateException("The order changed while it was being updated; check its status");
            }
            statusChangeRepository.save(OrderStatusChange.of(orderId, OrderStatus.DELIVERED, now, null));
            outboxWriter.add(KafkaConfig.ORDER_EVENTS_TOPIC, orderId.toString(), OrderDeliveredEvent.of(order, now));
        });
        log.info("Order {} DELIVERED, marked by {}", orderId, adminId);
        return orderResponses.of(findOrder(orderId));
    }

    // India Post, tracking EE123456789IN
    private static String shipment(String carrier, String trackingNumber) {
        return trackingNumber == null ? carrier : carrier + ", tracking " + trackingNumber;
    }

    private Order findOrder(UUID orderId) {
        return orderRepository.findWithItemsById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
