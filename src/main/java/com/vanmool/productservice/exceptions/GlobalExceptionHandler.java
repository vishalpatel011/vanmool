package com.vanmool.productservice.exceptions;

import com.vanmool.productservice.dtos.ErrorDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(NullPointerException.class)
    public ErrorDto handleNullPointerException() {
        ErrorDto errorDto = new ErrorDto();
        errorDto.setMessage("Null value encountered");
        errorDto.setStatus("Failure");
        return errorDto;
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorDto> handleProductNotFoundException(Exception e) {
        ErrorDto errorDto = new ErrorDto();
        errorDto.setStatus("Failure");
        errorDto.setMessage(e.getMessage());
        return new ResponseEntity<>(errorDto, HttpStatus.NOT_FOUND );
    }
}
