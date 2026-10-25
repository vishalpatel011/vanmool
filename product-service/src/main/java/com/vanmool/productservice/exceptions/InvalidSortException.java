package com.vanmool.productservice.exceptions;

import java.util.Set;

public class InvalidSortException extends RuntimeException {
    public InvalidSortException(String property, Set<String> sortableProperties) {
        super("Cannot sort by '" + property + "'; sort by one of " + sortableProperties.stream().sorted().toList());
    }
}
