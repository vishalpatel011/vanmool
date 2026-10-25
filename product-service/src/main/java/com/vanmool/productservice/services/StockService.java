package com.vanmool.productservice.services;

import com.vanmool.productservice.dtos.ReservationItemRequestDto;
import com.vanmool.productservice.dtos.StockReservationResponseDto;
import com.vanmool.productservice.dtos.StockResponseDto;
import com.vanmool.productservice.exceptions.InsufficientStockException;
import com.vanmool.productservice.exceptions.InvalidRequestException;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.exceptions.ReservationConflictException;
import com.vanmool.productservice.models.Product;
import com.vanmool.productservice.models.ReservationStatus;
import com.vanmool.productservice.models.ReservedItem;
import com.vanmool.productservice.models.Stock;
import com.vanmool.productservice.models.StockReservation;
import com.vanmool.productservice.repositories.ProductRepository;
import com.vanmool.productservice.repositories.StockRepository;
import com.vanmool.productservice.repositories.StockReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Stock and the reservations orders hold on it. This is the inventory part of the catalog: it lives in product-service
// for now (one database, one transaction with the product rows) and has its own tables, endpoints and code, so it can
// move to an inventory-service later without changing order-service's calls.
//
// Reservation lifecycle: order-service reserves when an order is placed (units taken from stock), and releases when
// the order is cancelled (units put back). Both are safe to repeat: they are keyed by the order id.
@Service
public class StockService {
    private final ProductRepository productRepository;
    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;

    public StockService(ProductRepository productRepository, StockRepository stockRepository,
                        StockReservationRepository reservationRepository) {
        this.productRepository = productRepository;
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
    }

    // created is false when the reservation already existed (the same request sent again)
    public record ReservationResult(StockReservationResponseDto reservation, boolean created) {
    }

    public StockResponseDto getStock(UUID productId) throws ProductNotFoundException {
        checkProductExists(productId);
        int quantity = stockRepository.findById(productId).map(Stock::getQuantity).orElse(0);
        return new StockResponseDto(productId, quantity);
    }

    @Transactional
    public StockResponseDto adjustStock(UUID productId, int change) throws ProductNotFoundException {
        if (change == 0) {
            throw new InvalidRequestException("change: must not be 0");
        }
        checkProductExists(productId);
        if (change > 0) {
            stockRepository.add(productId, change);
        } else if (stockRepository.take(productId, -change) == 0) {
            // Removing more units than there are would make the count negative
            throw new InsufficientStockException(List.of(productId));
        }
        return new StockResponseDto(productId, stockRepository.findById(productId).map(Stock::getQuantity).orElse(0));
    }

    // All or nothing: one transaction takes the units of every product. If one product has too few, the exception
    // rolls back the units already taken from the others. The prices returned are read in the same transaction.
    @Transactional
    public ReservationResult reserve(UUID orderId, List<ReservationItemRequestDto> items) throws ProductNotFoundException {
        Map<UUID, Integer> requested = items.stream().collect(Collectors.toMap(
                ReservationItemRequestDto::getProductId, ReservationItemRequestDto::getQuantity,
                (first, second) -> {
                    throw new InvalidRequestException("items: each product may appear only once");
                }));

        // The same order again: answer with what was reserved the first time, without taking any stock
        Optional<StockReservation> existing = reservationRepository.findById(orderId);
        if (existing.isPresent()) {
            return new ReservationResult(replay(existing.get(), requested), false);
        }

        Map<UUID, Product> products = productRepository.findAllByIdInAndDeletedFalse(requested.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (UUID productId : requested.keySet()) {
            if (!products.containsKey(productId)) {
                throw new ProductNotFoundException("Product with id " + productId + " not found");
            }
        }

        // Rows are locked in the same order (by product id) in every reservation and release. Two orders for the
        // same two products then never wait for each other in a circle, which would be a deadlock
        List<UUID> productIds = requested.keySet().stream().sorted().toList();
        List<UUID> insufficient = new ArrayList<>();
        for (UUID productId : productIds) {
            if (stockRepository.take(productId, requested.get(productId)) == 0) {
                insufficient.add(productId);
            }
        }
        if (!insufficient.isEmpty()) {
            throw new InsufficientStockException(insufficient);
        }

        StockReservation reservation = new StockReservation();
        reservation.setOrderId(orderId);
        reservation.setStatus(ReservationStatus.RESERVED);
        for (UUID productId : productIds) {
            Product product = products.get(productId);
            reservation.getItems().add(new ReservedItem(productId, product.getName(), product.getPrice(),
                    requested.get(productId)));
        }
        return new ReservationResult(StockReservationResponseDto.from(reservationRepository.save(reservation)), true);
    }

    // Puts the units back. Releasing twice, or releasing an order that never reserved anything, changes nothing
    @Transactional
    public void release(UUID orderId) {
        Optional<StockReservation> reservation = reservationRepository.findForUpdate(orderId);
        if (reservation.isEmpty() || reservation.get().getStatus() == ReservationStatus.RELEASED) {
            return;
        }
        reservation.get().getItems().stream()
                .sorted(Comparator.comparing(ReservedItem::getProductId))
                .forEach(item -> stockRepository.putBack(item.getProductId(), item.getQuantity()));
        reservation.get().setStatus(ReservationStatus.RELEASED);
    }

    private StockReservationResponseDto replay(StockReservation reservation, Map<UUID, Integer> requested) {
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new ReservationConflictException("The reservation for order " + reservation.getOrderId()
                    + " has been released");
        }
        Map<UUID, Integer> reserved = reservation.getItems().stream()
                .collect(Collectors.toMap(ReservedItem::getProductId, ReservedItem::getQuantity));
        if (!reserved.equals(requested)) {
            throw new ReservationConflictException("Order " + reservation.getOrderId()
                    + " already has a reservation with other items");
        }
        return StockReservationResponseDto.from(reservation);
    }

    private void checkProductExists(UUID productId) throws ProductNotFoundException {
        if (!productRepository.existsByIdAndDeletedFalse(productId)) {
            throw new ProductNotFoundException("Product with id " + productId + " not found");
        }
    }
}
