package com.vanmool.cartservice.exceptions;

import com.vanmool.cartservice.dtos.ErrorDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
    public ResponseEntity<ErrorDto> handleProductNotFound(ProductNotFoundException e, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(CartLimitExceededException.class)
    public ResponseEntity<ErrorDto> handleCartLimitExceeded(CartLimitExceededException e, HttpServletRequest request) {
        // 409: the request is valid on its own, but not together with what the cart already holds
        return buildError(HttpStatus.CONFLICT, "CART_LIMIT_EXCEEDED", e.getMessage(), request);
    }

    @ExceptionHandler(NotAUserTokenException.class)
    public ResponseEntity<ErrorDto> handleNotAUserToken(NotAUserTokenException e, HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), request);
    }

    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ResponseEntity<ErrorDto> handleProductServiceUnavailable(ProductServiceUnavailableException e, HttpServletRequest request) {
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_SERVICE_UNAVAILABLE", e.getMessage(), request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorDto> handleRedisFailure(DataAccessException e, HttpServletRequest request) {
        // Redis is down, unreachable or too slow. Carts live only there, so there is nothing to fall back to
        log.error("Redis failed on {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "CART_UNAVAILABLE", "Carts are not available right now", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDto> handleInvalidArgument(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        // For example /cart/items/abc: the product id is not a UUID
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadableBody(HttpServletRequest request) {
        // A missing body, broken JSON, or a value of the wrong type
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
