package com.vanmool.orderservice.exceptions;

import com.vanmool.orderservice.dtos.ErrorDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorDto> handleOrderNotFound(OrderNotFoundException e, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", e.getMessage(), request);
    }

    // 409 for the next four: the request is valid on its own, but not with the current state of the cart, the
    // stock or the order
    @ExceptionHandler(CartEmptyException.class)
    public ResponseEntity<ErrorDto> handleCartEmpty(CartEmptyException e, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "CART_EMPTY", e.getMessage(), request);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorDto> handleInsufficientStock(InsufficientStockException e, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", e.getMessage(), request);
    }

    @ExceptionHandler(ProductUnavailableException.class)
    public ResponseEntity<ErrorDto> handleProductUnavailable(ProductUnavailableException e, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE", e.getMessage(), request);
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorDto> handleInvalidOrderState(InvalidOrderStateException e, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "INVALID_ORDER_STATE", e.getMessage(), request);
    }

    @ExceptionHandler(NotAUserTokenException.class)
    public ResponseEntity<ErrorDto> handleNotAUserToken(NotAUserTokenException e, HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), request);
    }

    @ExceptionHandler(CartServiceUnavailableException.class)
    public ResponseEntity<ErrorDto> handleCartServiceUnavailable(CartServiceUnavailableException e, HttpServletRequest request) {
        log.warn("cart-service failed on {} {}: {}", request.getMethod(), request.getRequestURI(), e.getCause().getMessage());
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "CART_SERVICE_UNAVAILABLE", e.getMessage(), request);
    }

    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ResponseEntity<ErrorDto> handleProductServiceUnavailable(ProductServiceUnavailableException e, HttpServletRequest request) {
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_SERVICE_UNAVAILABLE", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDto> handleInvalidArgument(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        // For example /orders/abc: the order id is not a UUID
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for '" + e.getName() + "'", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequestBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        // Name every failing field, sorted so the message is stable
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorDto> handleInvalidIdempotencyKey(InvalidIdempotencyKeyException e, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadableBody(HttpServletRequest request) {
        // A missing body, broken JSON, or a value of the wrong type
        return buildError(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or is not valid JSON for this request", request);
    }

    // The database is unreachable, or gave no connection within spring.datasource.hikari.connection-timeout. That is
    // temporary, so 503 rather than 500: the client may try again. Nothing about the database goes to the client
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ErrorDto> handleDatabaseUnavailable(Exception e, HttpServletRequest request) {
        log.error("Database unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service is not available right now; please try again shortly", request);
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
