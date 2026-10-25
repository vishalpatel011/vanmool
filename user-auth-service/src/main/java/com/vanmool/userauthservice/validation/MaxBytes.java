package com.vanmool.userauthservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Limits a String's length in UTF-8 bytes rather than characters. BCrypt reads at most 72 bytes of a password, and
// a Gujarati or Hindi character takes 3 bytes, so @Size(max = 72) alone would let longer passwords through.
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxBytesValidator.class)
public @interface MaxBytes {
    int value();

    String message() default "must be at most {value} bytes long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
