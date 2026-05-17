package com.dalai.llama.creator.config;

import com.dalai.llama.creator.service.BillingWalletService;
import com.dalai.llama.creator.service.BillingWalletService.WalletBalanceCheckException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class BillingWalletGuardInterceptor implements HandlerInterceptor {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final CreatorProperties properties;
    private final BillingWalletService billingWalletService;
    private final ObjectMapper objectMapper;

    public BillingWalletGuardInterceptor(
            CreatorProperties properties,
            BillingWalletService billingWalletService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.billingWalletService = billingWalletService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.getBilling().isWalletGuardEnabled() || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        UUID tenantId = parseTenantId(request.getHeader(TENANT_HEADER));
        if (tenantId == null) {
            writeError(
                    response,
                    HttpStatus.BAD_REQUEST,
                    "Tenant id is required",
                    "X-Tenant-ID must be a valid UUID before creator actions can run.",
                    null
            );
            return false;
        }

        BigDecimal minimumBalance = properties.getBilling().getMinimumWalletBalance();
        try {
            BigDecimal currentBalance = billingWalletService.getWalletBalance(tenantId);
            if (currentBalance.compareTo(minimumBalance) < 0) {
                writeError(
                        response,
                        HttpStatus.PAYMENT_REQUIRED,
                        "Insufficient balance",
                        "Insufficient balance. Minimum wallet balance is " + minimumBalance + ".",
                        currentBalance
                );
                return false;
            }
            return true;
        } catch (WalletBalanceCheckException ex) {
            writeError(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Wallet check failed",
                    "Unable to verify wallet balance.",
                    null
            );
            return false;
        }
    }

    private UUID parseTenantId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void writeError(
            HttpServletResponse response,
            HttpStatus status,
            String error,
            String message,
            BigDecimal currentBalance
    ) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("minimumBalance", properties.getBilling().getMinimumWalletBalance());
        if (currentBalance != null) {
            body.put("currentBalance", currentBalance);
        }

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
