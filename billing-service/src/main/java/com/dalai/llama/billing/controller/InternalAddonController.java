package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.AddonService;
import com.dalai.llama.billing.service.AddonService.AddonCode;
import com.dalai.llama.billing.service.AddonService.AddonOffer;
import com.dalai.llama.billing.service.AddonService.AddonPurchase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** tenant-service -> here: what add-ons cost, and buying one from the wallet (mesh-internal). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalAddonController {

    private final AddonService addonService;

    public record AddonPurchaseRequest(@NotNull AddonCode code, @NotBlank @Size(max = 100) String idempotencyKey) {
    }

    @GetMapping("/addons")
    public ResponseEntity<List<AddonOffer>> catalog(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(addonService.catalog());
    }

    @PostMapping("/wallet/addon-purchase")
    public ResponseEntity<AddonPurchase> purchase(@PathVariable UUID tenantId, @Valid @RequestBody AddonPurchaseRequest request) {
        return ResponseEntity.ok(addonService.purchase(tenantId, request.code(), request.idempotencyKey()));
    }
}
