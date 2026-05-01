package com.dalai.llama.tenant.service.impl;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tiny helper bean that wraps a unit of work in a JPA transaction.
 * Exists so async code can open a Hibernate session on the async thread
 * without the orchestrator needing to self-inject.
 */
@Component
@RequiredArgsConstructor
public class ProvisioningTxRunner {

    @Transactional
    public void run(UUID tenantAppId, Consumer<UUID> work) {
        work.accept(tenantAppId);
    }
}