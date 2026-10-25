package com.vanmool.productservice.controllers;

import com.vanmool.productservice.dtos.ReservedItemResponseDto;
import com.vanmool.productservice.dtos.StockReservationResponseDto;
import com.vanmool.productservice.dtos.StockResponseDto;
import com.vanmool.productservice.exceptions.InsufficientStockException;
import com.vanmool.productservice.exceptions.InvalidRequestException;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.exceptions.ReservationConflictException;
import com.vanmool.productservice.models.ReservationStatus;
import com.vanmool.productservice.services.StockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web layer test of the stock endpoints: JSON in and out, validation, status codes and error codes. StockService is a mock.
@WebMvcTest(StockController.class)
class StockControllerWebMvcTest {
    private static final UUID ORDER_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID BASKET_ID = UUID.fromString("150dac81-6130-4903-9385-9b682db879b3");
    private static final String RESERVATION_REQUEST = """
            {
              "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
              "items": [ { "productId": "150dac81-6130-4903-9385-9b682db879b3", "quantity": 2 } ]
            }""";
    private static final StockReservationResponseDto RESERVATION = new StockReservationResponseDto(ORDER_ID,
            ReservationStatus.RESERVED,
            List.of(new ReservedItemResponseDto(BASKET_ID, "Handcrafted Bamboo Basket", new BigDecimal("499.99"), 2)));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Nested
    @DisplayName("POST /stock-reservations")
    class Reserve {
        @Test
        void newReservation_returns201WithNamesAndPrices() throws Exception {
            // Arrange
            when(stockService.reserve(eq(ORDER_ID), any())).thenReturn(new StockService.ReservationResult(RESERVATION, true));

            // Act & Assert
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(content().json("""
                            {
                              "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                              "status": "RESERVED",
                              "items": [
                                {
                                  "productId": "150dac81-6130-4903-9385-9b682db879b3",
                                  "name": "Handcrafted Bamboo Basket",
                                  "unitPrice": 499.99,
                                  "quantity": 2
                                }
                              ]
                            }""", JsonCompareMode.STRICT));
        }

        @Test
        void repeatedRequest_returns200WithTheSameReservation() throws Exception {
            // Arrange
            when(stockService.reserve(eq(ORDER_ID), any())).thenReturn(new StockService.ReservationResult(RESERVATION, false));

            // Act & Assert
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()));
        }

        @Test
        void notEnoughStock_returns409() throws Exception {
            // Arrange
            when(stockService.reserve(eq(ORDER_ID), any())).thenThrow(new InsufficientStockException(List.of(BASKET_ID)));

            // Act & Assert
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION_REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.message").value("Not enough stock for products [" + BASKET_ID + "]"));
        }

        @Test
        void unknownProduct_returns404() throws Exception {
            // Arrange
            when(stockService.reserve(eq(ORDER_ID), any()))
                    .thenThrow(new ProductNotFoundException("Product with id " + BASKET_ID + " not found"));

            // Act & Assert
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION_REQUEST))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
        }

        @Test
        void sameOrderWithOtherItems_returns409() throws Exception {
            // Arrange
            when(stockService.reserve(eq(ORDER_ID), any()))
                    .thenThrow(new ReservationConflictException("Order " + ORDER_ID + " already has a reservation with other items"));

            // Act & Assert
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION_REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("RESERVATION_CONFLICT"));
        }

        @Test
        void invalidRequest_returns400NamingEveryField_withoutCallingTheService() throws Exception {
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"items\": [ { \"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": 0 } ]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("items[0].quantity: must be greater than or equal to 1; orderId: must not be null"));
            verifyNoInteractions(stockService);
        }

        @Test
        void noItems_returns400() throws Exception {
            mockMvc.perform(post("/stock-reservations").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\", \"items\": []}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("items: must not be empty"));
            verifyNoInteractions(stockService);
        }
    }

    @Test
    void release_returns204() throws Exception {
        mockMvc.perform(delete("/stock-reservations/{orderId}", ORDER_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(stockService).release(ORDER_ID);
    }

    @Nested
    @DisplayName("GET /products/{id}/stock and POST /products/{id}/stock-adjustments")
    class Stock {
        @Test
        void getStock_returnsTheCount() throws Exception {
            // Arrange
            when(stockService.getStock(BASKET_ID)).thenReturn(new StockResponseDto(BASKET_ID, 12));

            // Act & Assert
            mockMvc.perform(get("/products/{id}/stock", BASKET_ID))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"productId\": \"150dac81-6130-4903-9385-9b682db879b3\", \"quantity\": 12}",
                            JsonCompareMode.STRICT));
        }

        @Test
        void adjust_returnsTheNewCount() throws Exception {
            // Arrange
            when(stockService.adjustStock(BASKET_ID, 10)).thenReturn(new StockResponseDto(BASKET_ID, 22));

            // Act & Assert
            mockMvc.perform(post("/products/{id}/stock-adjustments", BASKET_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"change\": 10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.quantity").value(22));
        }

        @Test
        void removingTooMany_returns409() throws Exception {
            // Arrange
            when(stockService.adjustStock(BASKET_ID, -50)).thenThrow(new InsufficientStockException(List.of(BASKET_ID)));

            // Act & Assert
            mockMvc.perform(post("/products/{id}/stock-adjustments", BASKET_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"change\": -50}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));
        }

        @Test
        void changeOfZero_returns400() throws Exception {
            // Arrange
            when(stockService.adjustStock(BASKET_ID, 0)).thenThrow(new InvalidRequestException("change: must not be 0"));

            // Act & Assert
            mockMvc.perform(post("/products/{id}/stock-adjustments", BASKET_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"change\": 0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("change: must not be 0"));
        }

        @Test
        void changeOutOfRange_returns400_withoutCallingTheService() throws Exception {
            mockMvc.perform(post("/products/{id}/stock-adjustments", BASKET_ID)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"change\": 1000000}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("change: must be less than or equal to 100000"));
            verifyNoInteractions(stockService);
        }

        @Test
        void unknownProduct_returns404() throws Exception {
            // Arrange
            when(stockService.getStock(BASKET_ID)).thenThrow(new ProductNotFoundException("Product with id " + BASKET_ID + " not found"));

            // Act & Assert
            mockMvc.perform(get("/products/{id}/stock", BASKET_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
        }
    }
}
