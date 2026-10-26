package com.vanmool.apigateway.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// The gateway's own error answers, in the same JSON as the services' (timestamp, status, error, message, path):
//   404 NOT_FOUND            no route for the path (also the internal endpoints, which are not routed)
//   503 SERVICE_UNAVAILABLE  the service has no instance up, or the one picked cannot be reached
//   504 GATEWAY_TIMEOUT      the service did not answer within 10 s
// The messages say what happened without internal details (host names, ports, exception texts).
@Component
public class GatewayErrorAttributes extends DefaultErrorAttributes {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorAttributes.class);

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        Throwable error = getError(request);
        HttpStatus status = statusOf(error);
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("Unexpected error on {} {}", request.method(), request.path(), error);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("timestamp", Instant.now());
        attributes.put("status", status.value());
        attributes.put("error", status.name());
        attributes.put("message", messageFor(status));
        attributes.put("path", request.path());
        return attributes;
    }

    static HttpStatus statusOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
        if (error instanceof ResponseStatusException responseStatus) {
            HttpStatus status = HttpStatus.resolve(responseStatus.getStatusCode().value());
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String messageFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "There is no API at this path";
            case SERVICE_UNAVAILABLE -> "The service for this request is not available right now";
            case GATEWAY_TIMEOUT -> "The service for this request did not answer in time";
            case INTERNAL_SERVER_ERROR -> "Something went wrong on our side";
            default -> status.getReasonPhrase();
        };
    }
}
