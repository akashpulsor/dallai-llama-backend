package com.dalai.llama.tenant.leadmanagement.service.impl;

import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.repository.CreatorEmailIdentityRepository;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** On startup, every creator still on a {@code cr_<uuid>@} address gets a readable one
 * ({@code asha.rao@}); mail from a random-looking address tends to land in Promotions. A no-op once
 * none are left. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyCreatorAddressUpgrade {

    private final CreatorEmailIdentityRepository repository;
    private final CreatorEmailIdentityService identities;

    @EventListener(ApplicationReadyEvent.class)
    public int upgrade() {
        int upgraded = 0;
        for (CreatorEmailIdentity legacy : repository.findByLocalPartStartingWith(CreatorEmailIdentityServiceImpl.LEGACY_PREFIX)) {
            try {
                identities.provisionForCreator(legacy.getTenantId(), legacy.getDisplayName());
                upgraded++;
            } catch (RuntimeException e) {
                log.warn("Could not give tenant {} a readable address: {}", legacy.getTenantId(), e.getMessage());
            }
        }
        if (upgraded > 0) log.info("Gave {} creators a readable outreach address", upgraded);
        return upgraded;
    }
}
