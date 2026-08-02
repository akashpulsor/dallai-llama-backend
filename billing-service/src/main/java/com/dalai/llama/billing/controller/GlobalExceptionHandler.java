package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.exception.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;                // ← ADD
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@Slf4j                                            // ← ADD
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class ErrorResponse {
        private String error;
        private String message;
        private int status;
        private Instant timestamp;
    }

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException ex) {
        log.warn("Wallet not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .error("WALLET_NOT_FOUND")
                        .message(ex.getMessage())
                        .status(HttpStatus.NOT_FOUND.value())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        log.warn("Insufficient balance: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ErrorResponse.builder()
                        .error("INSUFFICIENT_BALANCE")
                        .message(ex.getMessage())
                        .status(HttpStatus.PAYMENT_REQUIRED.value())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(RatePlanNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRatePlanNotFound(RatePlanNotFoundException ex) {
        log.warn("Rate plan not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .error("RATE_PLAN_NOT_FOUND")
                        .message(ex.getMessage())
                        .status(HttpStatus.NOT_FOUND.value())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ErrorResponse> handlePaymentFailed(PaymentFailedException ex) {
        HttpStatus status = paymentFailureStatus(ex);
        log.warn("Payment failed: {}", ex.getMessage());
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder()
                        .error("PAYMENT_FAILED")
                        .message(ex.getMessage())
                        .status(status.value())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(DuplicateCdrException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCdr(DuplicateCdrException ex) {
        log.warn("Duplicate CDR: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.builder()
                        .error("DUPLICATE_CDR")
                        .message(ex.getMessage())
                        .status(HttpStatus.CONFLICT.value())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .error("BAD_REQUEST")
                        .message(ex.getMessage())
                        .status(HttpStatus.BAD_REQUEST.value())
                        .timestamp(Instant.now())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);          // ← THE CRITICAL FIX
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .error("INTERNAL_ERROR")
                        .message("An unexpected error occurred")
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .timestamp(Instant.now())
                        .build());
    }

    private HttpStatus paymentFailureStatus(PaymentFailedException ex) {
        String text = (ex.getMessage() + " " + causeMessages(ex)).toLowerCase();
        if (text.contains("auth") || text.contains("unauthorized") || text.contains("invalid key")) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (text.contains("failed to create payment order")
                || text.contains("failed to create razorpay order")
                || text.contains("razorpay order")) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private String causeMessages(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable.getCause();
        while (current != null) {
            messages.append(' ').append(current.getMessage());
            current = current.getCause();
        }
        return messages.toString();
    }
}
