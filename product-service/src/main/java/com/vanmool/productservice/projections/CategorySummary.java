package com.vanmool.productservice.projections;

import lombok.AllArgsConstructor;
import lombok.Getter;

// Class-based projection: a JPQL "SELECT new ...CategorySummary(...)" calls this constructor once per row,
// so only the selected values are read, not whole entities. Hibernate checks at startup that the constructor exists.
@Getter
@AllArgsConstructor
public class CategorySummary {
    private final String name;
    private final Long productCount;
}
