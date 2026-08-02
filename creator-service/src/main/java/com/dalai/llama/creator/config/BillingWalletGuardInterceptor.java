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
        if (!isModelExecutionRequest(request)) {
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

    private boolean isModelExecutionRequest(HttpServletRequest request) {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        String method = request.getMethod();

        if (HttpMethod.GET.matches(method)) {
            return path.matches(".*/api/v1/creator/trends/[^/]+/insight$");
        }
        if (!HttpMethod.POST.matches(method) && !HttpMethod.PUT.matches(method) && !HttpMethod.PATCH.matches(method)) {
            return false;
        }

        if (path.endsWith("/trends/predict")) {
            return true;
        }

        if (path.endsWith("/creator/angles/suggest")) {
            return true;
        }

        if (path.contains("/api/v1/creator/locked-ideas/")) {
            return path.endsWith("/ideas/generate")
                    || path.endsWith("/ideas/generate-async")
                    || path.endsWith("/script/generate")
                    || path.endsWith("/screenplay/generate")
                    || path.endsWith("/screenplay/generate-async");
        }

        if (path.contains("/api/v1/creator/storyboards/scripts/")) {
            return path.endsWith("/plans/generate-async")
                    || path.endsWith("/videos/generate-async")
                    || path.endsWith("/generate")
                    || path.endsWith("/generate-async")
                    || path.endsWith("/shots/insert")
                    || path.endsWith("/studio-polish-async")
                    || path.endsWith("/enhance-all-async")
                    || (path.contains("/shots/") && path.contains("/images/"))
                    || (path.contains("/shots/") && path.endsWith("/ai-edit"));
        }

        if (path.contains("/api/v1/creator/storyboards/videos/")) {
            return path.endsWith("/chat")
                    || path.endsWith("/generate-async")
                    || path.endsWith("/regenerate-async")
                    || path.endsWith("/final-render-async");
        }

        if (path.contains("/api/v1/creator/storyboards/shots/takes/")) {
            return path.endsWith("/sound-generate-async")
                    || path.endsWith("/review-async")
                    || path.endsWith("/enhance-preview-async")
                    || path.endsWith("/studio-polish-async")
                    || path.endsWith("/enhance-audio-async");
        }

        return false;
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
