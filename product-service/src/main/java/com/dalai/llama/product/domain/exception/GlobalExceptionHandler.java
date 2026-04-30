package com.dalai.llama.product.domain.exception;

import com.dalai.llama.product.domain.exception.*;
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

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFound(ProductNotFoundException ex) {
        log.warn("Product not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(PlanNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePlanNotFound(PlanNotFoundException ex) {
        log.warn("Plan not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DidNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDidNotFound(DidNotFoundException ex) {
        log.warn("DID not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "DID_NOT_FOUND", ex.getMessage());
    }

    // ==================== 409 CONFLICT ====================

    @ExceptionHandler(DidNotAvailableException.class)
    public ResponseEntity<Map<String, Object>> handleDidNotAvailable(DidNotAvailableException ex) {
        log.warn("DID not available: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DID_NOT_AVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(SipEndpointConflictException.class)
    public ResponseEntity<Map<String, Object>> handleSipConflict(SipEndpointConflictException ex) {
        log.warn("SIP endpoint conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "SIP_ENDPOINT_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "ILLEGAL_STATE", ex.getMessage());
    }

    // ==================== 402 PAYMENT REQUIRED ====================

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientBalance(InsufficientBalanceException ex) {
        log.warn("Insufficient balance: {}", ex.getMessage());
        return buildResponse(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", ex.getMessage());
    }

    // ==================== 403 FORBIDDEN ====================

    @ExceptionHandler(BillingBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBillingBlocked(BillingBlockedException ex) {
        log.warn("Billing blocked: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "BILLING_BLOCKED", ex.getMessage());
    }

    @ExceptionHandler(EntitlementExceededException.class)
    public ResponseEntity<Map<String, Object>> handleEntitlementExceeded(EntitlementExceededException ex) {
        log.warn("Entitlement exceeded: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "ENTITLEMENT_EXCEEDED", ex.getMessage());
    }

    // ==================== 502 BAD GATEWAY (external APIs) ====================

    @ExceptionHandler(DidwwApiException.class)
    public ResponseEntity<Map<String, Object>> handleDidwwApi(DidwwApiException ex) {
        log.error("DIDWW API error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.BAD_GATEWAY, "DIDWW_API_ERROR", "DID provider error: " + ex.getMessage());
    }

    @ExceptionHandler(EpsilonApiException.class)
    public ResponseEntity<Map<String, Object>> handleEpsilonApi(EpsilonApiException ex) {
        log.error("Epsilon API error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.BAD_GATEWAY, "EPSILON_API_ERROR", "SIP trunk provider error: " + ex.getMessage());
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

    // ==================== 500 CATCH-ALL ====================

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}