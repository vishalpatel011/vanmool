package com.vanmool.userauthservice.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// Every error response has this shape, the same as in product-service and in Spring Boot's own errors
@Getter
@Setter
public class ErrorDto {
    private Instant timestamp;
    private int status;
    // A fixed code clients can check, such as EMAIL_ALREADY_REGISTERED; the message is for people and may change
    private String error;
    private String message;
    private String path;
}
