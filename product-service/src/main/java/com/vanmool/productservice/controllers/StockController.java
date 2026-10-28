package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.StockAdjustmentRequestDto;
import com.vanmool.productservice.dtos.StockReservationRequestDto;
import com.vanmool.productservice.dtos.StockReservationResponseDto;
import com.vanmool.productservice.dtos.StockResponseDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.services.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Stock of a product (for producers) and stock reservations (for order-service). Not protected yet, like the other
// product writes: the API gateway step adds roles for adjustments and keeps reservations internal.
@RestController
@Tag(name = "Stock", description = "Units for sale, and the reservations orders make")
public class StockController {
    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @Operation(summary = "Units that can be sold now")
    @GetMapping("/products/{id}/stock")
    public StockResponseDto getStock(@PathVariable("id") UUID productId) throws ProductNotFoundException {
        return stockService.getStock(productId);
    }

    @Operation(summary = "SELLER, ADMIN: add or remove units")
    @PostMapping("/products/{id}/stock-adjustments")
    public StockResponseDto adjustStock(@PathVariable("id") UUID productId,
                                        @Valid @RequestBody StockAdjustmentRequestDto request) throws ProductNotFoundException {
        return stockService.adjustStock(productId, request.getChange());
    }

    // 201 when the units were taken now; 200 when the same order's reservation already existed
    @Operation(summary = "Internal, for order-service (not reachable through the API gateway): reserve an order's units, all or nothing")
    @PostMapping("/stock-reservations")
    public ResponseEntity<StockReservationResponseDto> reserve(@Valid @RequestBody StockReservationRequestDto request)
            throws ProductNotFoundException {
        StockService.ReservationResult result = stockService.reserve(request.getOrderId(), request.getItems());
        return new ResponseEntity<>(result.reservation(), result.created() ? HttpStatus.CREATED : HttpStatus.OK);
    }

    // 204 in every case: afterwards, the order holds no units, which is what the caller asked for
    @Operation(summary = "Internal, for order-service: put an order's units back")
    @DeleteMapping("/stock-reservations/{orderId}")
    public ResponseEntity<Void> release(@PathVariable("orderId") UUID orderId) {
        stockService.release(orderId);
        return ResponseEntity.noContent().build();
    }
}
