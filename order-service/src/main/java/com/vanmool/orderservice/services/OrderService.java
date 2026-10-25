package com.vanmool.orderservice.services;

import com.vanmool.orderservice.clients.CartClient;
import com.vanmool.orderservice.clients.ProductClient;
import com.vanmool.orderservice.configs.KafkaConfig;
import com.vanmool.orderservice.dtos.OrderResponseDto;
import com.vanmool.orderservice.dtos.ShippingAddressDto;
import com.vanmool.orderservice.exceptions.CartEmptyException;
import com.vanmool.orderservice.exceptions.InvalidOrderStateException;
import com.vanmool.orderservice.exceptions.OrderNotFoundException;
import com.vanmool.orderservice.exceptions.ProductServiceUnavailableException;
import com.vanmool.orderservice.events.OrderCancelledEvent;
import com.vanmool.orderservice.events.OrderPlacedEvent;
import com.vanmool.orderservice.events.OutboxWriter;
import com.vanmool.orderservice.models.Order;
import com.vanmool.orderservice.models.OrderStatus;
import com.vanmool.orderservice.repositories.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Placing an order touches three services, and there is no transaction across them. Each step is arranged so that a
// failure leaves nothing behind, or something that can be undone:
//   1. read the cart (cart-service)                          fails: nothing has happened
//   2. reserve the stock under a new order id (product-service)  fails: nothing was taken (all or nothing there)
//   3. save the order and its OrderPlaced event (order_db, one transaction)   fails: the reservation is released again
//   4. empty the cart (cart-service)                          fails: logged; the order stands, the cart stays full
// Reserve and release are keyed by the order id, so repeating them is always safe. The OrderPlaced event goes to
// Kafka through the outbox: it exists exactly when the order exists.
// Remote calls are made outside any database transaction, so no database connection waits on another service.
@Slf4j
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, CartClient cartClient, ProductClient productClient,
                        OutboxWriter outboxWriter, TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.cartClient = cartClient;
        this.productClient = productClient;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public OrderResponseDto placeOrder(Customer customer, ShippingAddressDto shippingAddress, String idempotencyKey) {
        UUID userId = customer.id();
        String bearerToken = customer.bearerToken();
        // The same Idempotency-Key again (a double click, or a retry after a timeout): the order already placed
        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return OrderResponseDto.from(existing.get());
            }
        }

        List<CartClient.CartLine> lines = cartClient.getCartLines(bearerToken);
        if (lines.isEmpty()) {
            throw new CartEmptyException();
        }

        UUID orderId = UUID.randomUUID();
        ProductClient.Reservation reservation;
        try {
            reservation = productClient.reserve(orderId, lines);
        } catch (ProductServiceUnavailableException e) {
            // A timeout does not say whether product-service took the units before it failed; releasing makes sure
            releaseQuietly(orderId);
            throw e;
        }

        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(idempotencyKey);
        order.setShippingAddress(shippingAddress.toShippingAddress());
        reservation.items().forEach(item -> order.addItem(item.productId(), item.name(), item.unitPrice(), item.quantity()));
        order.setTotalAmount(order.calculateTotal());

        Order saved;
        try {
            saved = transactionTemplate.execute(status -> {
                Order inserted = orderRepository.saveAndFlush(order);
                outboxWriter.add(KafkaConfig.ORDER_EVENTS_TOPIC, orderId.toString(),
                        OrderPlacedEvent.of(inserted, customer.email(), clock.instant()));
                return inserted;
            });
        } catch (DataIntegrityViolationException e) {
            releaseQuietly(orderId);
            // Two requests with the same Idempotency-Key at the same moment: the unique key let only the first in
            if (idempotencyKey != null) {
                Optional<Order> first = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
                if (first.isPresent()) {
                    return OrderResponseDto.from(first.get());
                }
            }
            throw e;
        } catch (RuntimeException e) {
            releaseQuietly(orderId);
            throw e;
        }

        try {
            cartClient.clearCart(bearerToken);
        } catch (RuntimeException e) {
            log.warn("Order {} was placed, but the cart of user {} could not be emptied: {}", orderId, userId, e.getMessage());
        }
        return OrderResponseDto.from(saved);
    }

    // Newest first, always: order history has one natural order. The id breaks ties, so pages never overlap
    @Transactional(readOnly = true)
    public Page<OrderResponseDto> getOrders(UUID userId, Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        // Mapped inside the transaction: the items of the whole page are loaded here, in one batch query
        return orderRepository.findByUserId(userId, newestFirst).map(OrderResponseDto::from);
    }

    public OrderResponseDto getOrder(UUID userId, UUID orderId) {
        return OrderResponseDto.from(findOrder(userId, orderId));
    }

    // Marks the order cancelled and records OrderCancelled in one transaction. The status change is one conditional
    // UPDATE, so a cancel and a payment arriving together cannot both win. product-service releases the stock when it
    // reads the event; if it is down, the event waits in Kafka and the release happens when it is back (retried until
    // it succeeds). Releasing before the status change instead could put back the units of an order that was paid a
    // moment later, and those units could then be sold twice.
    // Cancelling a cancelled order again changes nothing and publishes nothing.
    public OrderResponseDto cancelOrder(Customer customer, UUID orderId) {
        Order order = findOrder(customer.id(), orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponseDto.from(order);
        }
        if (!order.getStatus().canMoveTo(OrderStatus.CANCELLED)) {
            throw new InvalidOrderStateException("An order that is " + order.getStatus() + " cannot be cancelled");
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (orderRepository.moveStatus(orderId, order.getStatus(), OrderStatus.CANCELLED) == 0) {
                throw new InvalidOrderStateException("The order changed while it was being cancelled; check its status");
            }
            outboxWriter.add(KafkaConfig.ORDER_EVENTS_TOPIC, orderId.toString(),
                    OrderCancelledEvent.of(order, customer.email(), clock.instant()));
        });
        return OrderResponseDto.from(findOrder(customer.id(), orderId));
    }

    private Order findOrder(UUID userId, UUID orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private void releaseQuietly(UUID orderId) {
        try {
            productClient.release(orderId);
        } catch (RuntimeException e) {
            // Both the order and the release failed. If product-service did reserve units for this order (after a
            // timeout it is unknown), they stay reserved until someone releases them
            log.error("Order {} failed and so did releasing its stock; if product-service reserved units for it, "
                    + "release them with DELETE /stock-reservations/{}", orderId, orderId, e);
        }
    }
}
