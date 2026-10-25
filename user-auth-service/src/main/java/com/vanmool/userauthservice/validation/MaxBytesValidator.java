package com.vanmool.userauthservice.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxBytesValidator implements ConstraintValidator<MaxBytes, String> {
    private int maxBytes;

    @Override
    public void initialize(MaxBytes constraint) {
        this.maxBytes = constraint.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null is left to @NotNull, as with the built-in constraints
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
