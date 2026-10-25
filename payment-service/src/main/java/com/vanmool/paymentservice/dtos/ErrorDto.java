package com.vanmool.paymentservice.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// Every error response has this shape, the same as in the other Vanmool services
@Getter
@Setter
public class ErrorDto {
    private Instant timestamp;
    private int status;
    // A fixed code clients can check, such as ORDER_NOT_PAYABLE; the message is for people and may change
    private String error;
    private String message;
    private String path;
}
