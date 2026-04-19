package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.BillingState;
import com.dalai.llama.billing.dto.response.BillingStateResponse;
import com.dalai.llama.billing.repository.BillingStateRepository;
import com.dalai.llama.billing.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Billing state management APIs")
public class BillingController {

    private final BillingStateRepository billingStateRepository;
    private final WalletService walletService;

    @GetMapping("/billing-state")
    @Operation(summary = "Get billing state", description = "Retrieve current billing state for a tenant")
    public ResponseEntity<BillingStateResponse> getBillingState(@PathVariable UUID tenantId) {
        BillingState state = billingStateRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingState.createDefault(tenantId));

        return ResponseEntity.ok(BillingStateResponse.builder()
                .state(state.getState())
                .graceExpiresAt(state.getGraceExpiresAt())
                .blockReason(state.getBlockReason())
                .blockedAt(state.getBlockedAt())
                .lastCheckedAt(state.getLastCheckedAt())
                .build());
    }


}
