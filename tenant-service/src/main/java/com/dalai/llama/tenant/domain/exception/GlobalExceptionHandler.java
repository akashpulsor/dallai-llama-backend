package com.dalai.llama.tenant.domain.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 404 NOT FOUND ====================

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTenantNotFound(TenantNotFoundException ex) {
        log.warn("Tenant not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(KeycloakUserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakUserNotFound(KeycloakUserNotFoundException ex) {
        log.warn("Keycloak user not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage());
    }

    // ==================== 409 CONFLICT ====================

    @ExceptionHandler(TenantAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleTenantAlreadyExists(TenantAlreadyExistsException ex) {
        log.warn("Tenant already exists: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "TENANT_ALREADY_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStateTransition(InvalidStateTransitionException ex) {
        log.warn("Invalid state transition: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "ILLEGAL_STATE", ex.getMessage());
    }

    // ==================== 502 BAD GATEWAY ====================

    @ExceptionHandler(KeycloakException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloak(KeycloakException ex) {
        log.error("Keycloak error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.BAD_GATEWAY, "KEYCLOAK_ERROR",
                "Identity provider error: " + ex.getMessage());
    }

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<Map<String, Object>> handleProvisioning(ProvisioningException ex) {
        log.error("Provisioning error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "PROVISIONING_ERROR", ex.getMessage());
    }

    // ==================== 401 UNAUTHORIZED ====================

    @ExceptionHandler(com.dalai.llama.tenant.leadmanagement.brand.BrandNotSignedInException.class)
    public ResponseEntity<Map<String, Object>> handleBrandNotSignedIn(
            com.dalai.llama.tenant.leadmanagement.brand.BrandNotSignedInException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "BRAND_SIGN_IN_REQUIRED", ex.getMessage());
    }

    // ==================== 402 PAYMENT REQUIRED ====================

    @ExceptionHandler(com.dalai.llama.tenant.leadmanagement.outreach.WalletTooLowException.class)
    public ResponseEntity<Map<String, Object>> handleWalletTooLow(
            com.dalai.llama.tenant.leadmanagement.outreach.WalletTooLowException ex) {
        return buildResponse(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", ex.getMessage());
    }

    // ==================== 503 SERVICE UNAVAILABLE ====================

    @ExceptionHandler(com.dalai.llama.tenant.youtube.client.YouTubeUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleYouTubeUnavailable(
            com.dalai.llama.tenant.youtube.client.YouTubeUnavailableException ex) {
        log.warn("YouTube unavailable: {}", ex.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "YOUTUBE_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamUnavailable(
            com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException ex) {
        log.warn("Upstream unavailable: {}", ex.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", ex.getMessage());
    }

    // ==================== 400 BAD REQUEST ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", errors);
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", errors);
    }

    /** Missing header, bad path/query value (e.g. an unknown enum), unreadable JSON body: the
     * caller's mistake, not a server error. */
    @ExceptionHandler({
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequestShape(Exception ex) {
        log.warn("Malformed request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ex.getMessage());
    }

    // ==================== 500 CATCH-ALL ====================

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException ex) {
        log.error("NullPointerException: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "A required value was missing. Please verify your request and try again.");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred";
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred";
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String, Object>> handleThrowable(Throwable ex) {
        log.error("Critical error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "A critical error occurred. Please contact support.");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message != null ? message : "An unexpected error occurred");
        return ResponseEntity.status(status).body(body);
    }
}
