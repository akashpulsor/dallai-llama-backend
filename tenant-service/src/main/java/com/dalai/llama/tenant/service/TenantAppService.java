package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.TenantApp;

import java.util.Optional;
import java.util.UUID;

public interface TenantAppService {

    Optional<TenantApp> getByDid(String did);

    Optional<TenantApp> getByTenantId(UUID tenantId);

    void provisionApp(UUID tenantAppId);
}