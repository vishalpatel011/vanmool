package com.vanmool.userauthservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.userauthservice.dtos.ErrorDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

// Spring Security refuses requests in its filters, before any controller runs, so GlobalExceptionHandler never sees
// those errors. This writes them in the same JSON format: 401 when the bearer token is missing or not acceptable,
// 403 when the token is fine but the user's roles do not allow the request.
@Component
public class RestSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public RestSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        // RFC 6750: a 401 for a bearer token tells the client which authentication scheme to use
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, request, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "The token is missing, invalid, expired or revoked");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your roles do not allow this request");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String error, String message)
            throws IOException {
        ErrorDto errorDto = new ErrorDto();
        errorDto.setTimestamp(Instant.now());
        errorDto.setStatus(status.value());
        errorDto.setError(error);
        errorDto.setMessage(message);
        errorDto.setPath(request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), errorDto);
    }
}
