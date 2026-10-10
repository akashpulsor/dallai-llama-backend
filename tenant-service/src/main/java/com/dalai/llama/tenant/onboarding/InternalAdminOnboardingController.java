package com.dalai.llama.tenant.onboarding;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ops-only backfill: gives every subscribed creator their email identity and public profile.
 * Mesh-internal under {@code /api/v1/internal/admin/**}, like the other admin controllers; never
 * published through the gateway. Safe to re-run: both steps are idempotent. */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/onboarding")
public class InternalAdminOnboardingController {

    private final TenantRepository tenantRepository;
    private final CreatorOnboardingService onboardingService;

    @PostMapping("/backfill")
    public BackfillResult backfill() {
        int onboarded = 0;
        int skipped = 0;
        List<UUID> incomplete = new ArrayList<>();
        for (Tenant tenant : tenantRepository.findAll()) {
            if (!onboardingService.isEligible(tenant)) {
                skipped++;
                continue;
            }
            CreatorOnboardingService.OnboardingResult result =
                    onboardingService.onSubscriptionActivated(tenant.getId(), tenant.getName());
            if (result.emailIdentityReady() && result.publicProfileReady()) onboarded++;
            else incomplete.add(tenant.getId());
        }
        log.info("Onboarding backfill: onboarded={} skipped={} incomplete={}", onboarded, skipped, incomplete.size());
        return new BackfillResult(onboarded, skipped, incomplete);
    }

    public record BackfillResult(int onboardedCount, int skippedNotSubscribedCount, List<UUID> incompleteTenantIds) {
    }
}
