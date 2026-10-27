package com.vanmool.paymentservice.exceptions;

import com.vanmool.paymentservice.dtos.ErrorDto;
import com.vanmool.paymentservice.gateways.GatewayNotEnabledException;
import com.vanmool.paymentservice.gateways.GatewayUnavailableException;
import com.vanmool.paymentservice.gateways.InvalidSignatureException;
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

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorDto> handlePaymentNotFound(PaymentNotFoundException e, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(OrderNotPayableException.class)
    public ResponseEntity<ErrorDto> handleOrderNotPayable(OrderNotPayableException e, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE", e.getMessage(), request);
    }

    @ExceptionHandler(PaymentInProgressException.class)
    public ResponseEntity<ErrorDto> handlePaymentInProgress(PaymentInProgressException e, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS", e.getMessage(), request);
    }

    @ExceptionHandler(GatewayNotEnabledException.class)
    public ResponseEntity<ErrorDto> handleGatewayNotEnabled(GatewayNotEnabledException e, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "GATEWAY_NOT_AVAILABLE", e.getMessage(), request);
    }

    // A forged or changed webhook or callback. Nothing about the expected signature is revealed
    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<ErrorDto> handleInvalidSignature(InvalidSignatureException e, HttpServletRequest request) {
        log.warn("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE", "The request could not be verified", request);
    }

    @ExceptionHandler(GatewayUnavailableException.class)
    public ResponseEntity<ErrorDto> handleGatewayUnavailable(GatewayUnavailableException e, HttpServletRequest request) {
        log.warn("Payment gateway failed on {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_GATEWAY_UNAVAILABLE",
                "Payments are not available right now; please try again shortly", request);
    }

    @ExceptionHandler(OrderServiceUnavailableException.class)
    public ResponseEntity<ErrorDto> handleOrderServiceUnavailable(OrderServiceUnavailableException e, HttpServletRequest request) {
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_SERVICE_UNAVAILABLE", e.getMessage(), request);
    }

    @ExceptionHandler(NotAUserTokenException.class)
    public ResponseEntity<ErrorDto> handleNotAUserToken(NotAUserTokenException e, HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorDto> handleInvalidArgument(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for '" + e.getName() + "'", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleInvalidRequestBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleUnreadableBody(HttpServletRequest request) {
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
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            ErrorDto errorDto = toErrorDto(status, status.name(), errorResponse.getBody().getDetail(), request);
            return new ResponseEntity<>(errorDto, errorResponse.getHeaders(), status);
        }
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
