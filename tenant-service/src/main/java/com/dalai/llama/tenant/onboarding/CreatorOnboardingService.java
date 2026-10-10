package com.dalai.llama.tenant.onboarding;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Everything a creator gets the moment their subscription activates: the partner email identity
 * and the public profile (CREATOR_SHOWCASE.md rule 1). Both steps are idempotent, so this runs
 * first on every delivery of {@code product.subscription.activated}, including redeliveries
 * after a failed attempt. One step failing never stops the other. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorOnboardingService {

    private final CreatorEmailIdentityService creatorEmailIdentityService;
    private final CreatorProfileService creatorProfileService;
    private final TenantAppRepository tenantAppRepository;

    public OnboardingResult onSubscriptionActivated(UUID tenantId, String name) {
        boolean emailReady = run("email identity", tenantId,
                () -> creatorEmailIdentityService.provisionForCreator(tenantId, name));
        boolean profileReady = run("public profile", tenantId,
                () -> creatorProfileService.ensureProfile(tenantId, name));
        return new OnboardingResult(emailReady, profileReady);
    }

    /** Backfill only touches creators who really subscribed: a live tenant with at least one app,
     * and an app only ever exists because subscription.activated created it. Registration alone
     * never gets an email identity or a public profile. */
    public boolean isEligible(Tenant tenant) {
        boolean live = tenant.getStatus() != TenantStatus.DELETED && tenant.getStatus() != TenantStatus.SUSPENDED;
        return live && tenantAppRepository.findFirstByTenantId(tenant.getId()).isPresent();
    }

    private boolean run(String step, UUID tenantId, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            log.error("Onboarding step '{}' failed for tenant {}; it re-runs on redelivery or backfill",
                    step, tenantId, e);
            return false;
        }
    }

    public record OnboardingResult(boolean emailIdentityReady, boolean publicProfileReady) {
    }
}
