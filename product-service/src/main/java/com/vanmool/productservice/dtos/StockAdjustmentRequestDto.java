package com.vanmool.productservice.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

// POST /products/{id}/stock-adjustments: units to add (a producer brings 10 more baskets: 10) or to remove (2 were
// damaged: -2). A change, not a new total, so it never overwrites units that orders took in the meantime
@Getter
@Setter
public class StockAdjustmentRequestDto {
    @NotNull
    @Min(-100_000)
    @Max(100_000)
    private Integer change;
}
