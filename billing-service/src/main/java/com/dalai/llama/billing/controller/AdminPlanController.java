package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.RatePlan;
import com.dalai.llama.billing.dto.AdminPlanUpsertRequest;
import com.dalai.llama.billing.dto.AdminPlanView;
import com.dalai.llama.billing.repository.RatePlanRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Plans management for the ops dashboard. Under /api/v1/internal/admin/plans -- the same
 * ops.dalaillama.in oauth2-proxy + dalai_admin perimeter as the rest of the admin API.
 *
 * <p>Deliberately narrow: list, create, update, activate/deactivate. Deletion is intentionally
 * not exposed -- a plan may be referenced by historical recurring_charges and dropping it would
 * corrupt the ledger. Deactivating a plan hides it from the enrolment UI without breaking
 * anything referencing it.
 */
@RestController
@RequestMapping("/api/v1/internal/admin/plans")
@RequiredArgsConstructor
public class AdminPlanController {

    private final RatePlanRepository ratePlanRepository;

    @GetMapping
    public ResponseEntity<List<AdminPlanView>> list() {
        List<AdminPlanView> plans = ratePlanRepository.findAll().stream()
                .sorted(Comparator.comparing(RatePlan::isActive).reversed()
                        .thenComparing(RatePlan::getCode))
                .map(AdminPlanView::from)
                .toList();
        return ResponseEntity.ok(plans);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<AdminPlanView> create(@Valid @RequestBody AdminPlanUpsertRequest req) {
        if (ratePlanRepository.findByCode(req.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plan with code '" + req.code() + "' already exists");
        }
        Instant now = Instant.now();
        RatePlan plan = RatePlan.builder()
                .id(UUID.randomUUID())
                .code(req.code())
                .name(req.name())
                .description(req.description())
                .amount(req.amount())
                .currency(req.currency())
                .frequency(req.frequency())
                .walletCreditPerCycle(req.walletCreditPerCycle())
                .isDefault(Boolean.TRUE.equals(req.isDefault()))
                .active(req.active() == null ? true : req.active())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return ResponseEntity.ok(AdminPlanView.from(ratePlanRepository.save(plan)));
    }

    @PutMapping("/{planId}")
    @Transactional
    public ResponseEntity<AdminPlanView> update(@PathVariable UUID planId,
                                                @Valid @RequestBody AdminPlanUpsertRequest req) {
        RatePlan plan = load(planId);
        // Code is immutable -- other tables (recurring_charges, transactions) reference it and
        // renaming would create ledger drift. Name/description/amount/etc are free to change.
        plan.setName(req.name());
        plan.setDescription(req.description());
        plan.setAmount(req.amount());
        plan.setCurrency(req.currency());
        plan.setFrequency(req.frequency());
        plan.setWalletCreditPerCycle(req.walletCreditPerCycle());
        if (req.isDefault() != null) plan.setDefault(req.isDefault());
        if (req.active() != null) plan.setActive(req.active());
        plan.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(AdminPlanView.from(ratePlanRepository.save(plan)));
    }

    @PostMapping("/{planId}/activate")
    @Transactional
    public ResponseEntity<AdminPlanView> activate(@PathVariable UUID planId) {
        RatePlan plan = load(planId);
        // Guard: refuse to activate a plan with amount 0 -- the caller would silently offer
        // "free" enrolment. Better to fail loudly than to let a legacy row become billable.
        if (plan.getAmount() == null || plan.getAmount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plan " + plan.getCode() + " has no amount set -- set an amount before activating");
        }
        plan.setActive(true);
        plan.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(AdminPlanView.from(ratePlanRepository.save(plan)));
    }

    @PostMapping("/{planId}/deactivate")
    @Transactional
    public ResponseEntity<AdminPlanView> deactivate(@PathVariable UUID planId) {
        RatePlan plan = load(planId);
        plan.setActive(false);
        plan.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(AdminPlanView.from(ratePlanRepository.save(plan)));
    }

    private RatePlan load(UUID planId) {
        return ratePlanRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No plan " + planId));
    }
}
