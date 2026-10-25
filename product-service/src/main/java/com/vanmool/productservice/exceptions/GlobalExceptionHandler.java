package com.vanmool.productservice.exceptions;

import com.vanmool.productservice.dtos.ErrorDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.fge.jsonpatch.JsonPatchException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorDto> handleProductNotFoundException(ProductNotFoundException e, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler({JsonPatchException.class, JsonProcessingException.class})
    public ResponseEntity<ErrorDto> handleInvalidPatch(HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "Patch could not be applied to the product", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDto> handleInvalidArgument(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        // For example /products/abc, where the id is not a valid UUID
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for '" + e.getName() + "'", request);
    }

    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ErrorDto> handleInvalidSort(InvalidSortException e, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequestBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        // A @Valid request body broke one or more rules; name every field, sorted so the message is stable
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorDto> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        // A patched product broke the same rules as a request body
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadableBody(HttpServletRequest request) {
        // A missing body, broken JSON, or a value of the wrong type such as "price": "abc"
        return buildError(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or is not valid JSON for this request", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleOtherExceptions(Exception e, HttpServletRequest request) {
        // Spring's own request errors (unknown path, wrong method, wrong Content-Type) carry their status, a safe
        // message and headers such as Allow on a 405
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            ErrorDto errorDto = toErrorDto(status, status.name(), errorResponse.getBody().getDetail(), request);
            return new ResponseEntity<>(errorDto, errorResponse.getHeaders(), status);
        }
        // Anything else is a bug: log the details for us, and give the client nothing internal
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side", request);
    }

    private ResponseEntity<ErrorDto> buildError(HttpStatus status, String error, String message, HttpServletRequest request) {
        return new ResponseEntity<>(toErrorDto(status, error, message, request), status);
    }

    private ErrorDto toErrorDto(HttpStatus status, String error, String message, HttpServletRequest request) {
        ErrorDto errorDto = new ErrorDto();
        errorDto.setTimestamp(Instant.now());
        errorDto.setStatus(status.value());
        errorDto.setError(error);
        errorDto.setMessage(message);
        errorDto.setPath(request.getRequestURI());
        return errorDto;
    }
}
