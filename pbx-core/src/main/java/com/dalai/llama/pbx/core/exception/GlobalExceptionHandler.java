package com.dalai.llama.pbx.core.exception;



import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler — consistent error response format across all APIs.
 *
 * Response format:
 *   {
 *     "status": 404,
 *     "error": "Not Found",
 *     "code": "TENANT_NOT_FOUND",
 *     "message": "Tenant not found: abc-123",
 *     "timestamp": "2026-03-15T10:30:00Z"
 *   }
 *
 * Internal APIs (/internal/**) still get this format — Kamailio http_client
 * and FreeSWITCH mod_xml_curl parse the HTTP status code, not the body.
 * But consistent JSON bodies help with debugging.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTenantNotFound(TenantNotFoundException ex) {
        log.warn("Tenant not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CallAuthDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleCallAuthDenied(CallAuthDeniedException ex) {
        log.warn("Call auth denied: {} — {}", ex.getCode(), ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ChannelLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleChannelLimit(ChannelLimitExceededException ex) {
        log.warn("Channel limit exceeded: {}", ex.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "CHANNEL_LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(EslConnectionException.class)
    public ResponseEntity<Map<String, Object>> handleEslConnection(EslConnectionException ex) {
        log.error("ESL connection error: {}", ex.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "ESL_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
