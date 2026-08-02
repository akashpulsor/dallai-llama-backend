package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.exception.CreatorAiOutputException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice(basePackages = "com.dalai.llama.creator.controller")
public class CreatorApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.put(error.getField(), friendlyFieldMessage(error.getField(), error.getDefaultMessage()))
        );
        String message = fields.values().stream().findFirst().orElse("Request validation failed.");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, message, request, fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String rawMessage = ex.getMostSpecificCause() == null ? ex.getMessage() : ex.getMostSpecificCause().getMessage();
        String message = rawMessage != null && rawMessage.contains("UUID")
                ? "Use a valid UUID for id fields. Placeholder values like project-she-almost are not accepted."
                : "Request body is not valid JSON for this endpoint.";
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, message, request, Map.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        Map<String, Object> body = errorBody(status, ex.getReason(), request, Map.of());
        if (ex instanceof CreatorAiOutputException aiOutputException && !aiOutputException.getDebugPayload().isEmpty()) {
            body.put("debug", aiOutputException.getDebugPayload());
        }
        return ResponseEntity
                .status(status)
                .body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(
            DataAccessException ex,
            HttpServletRequest request
    ) {
        boolean transientFailure = isTransientDatabaseFailure(ex);
        HttpStatus status = transientFailure
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = transientFailure
                ? "The project database is temporarily unavailable. Your saved review is preserved; retry this request shortly."
                : "The project database could not complete this request.";
        Map<String, Object> body = errorBody(status, message, request, Map.of());
        if (transientFailure) {
            body.put("retryAfterMs", 3000);
            return ResponseEntity
                    .status(status)
                    .header("Retry-After", "3")
                    .body(body);
        }
        return ResponseEntity
                .status(status)
                .body(body);
    }

    private boolean isTransientDatabaseFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (current instanceof java.sql.SQLException sqlException
                    && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("08")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("connection is closed")
                        || normalized.contains("connection has been closed")
                        || normalized.contains("i/o error occurred while sending to the backend")
                        || normalized.contains("unable to rollback against jdbc connection")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String friendlyFieldMessage(String field, String defaultMessage) {
        if ("mappings".equals(field)) {
            return "Map at least one story character to a cast profile before confirming cast.";
        }
        return defaultMessage == null || defaultMessage.isBlank()
                ? "Field is invalid: " + field
                : field + " " + defaultMessage;
    }

    private Map<String, Object> errorBody(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> fields
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message == null || message.isBlank() ? status.getReasonPhrase() : message);
        body.put("path", request == null ? "" : request.getRequestURI());
        body.put("fields", fields);
        return body;
    }
}
