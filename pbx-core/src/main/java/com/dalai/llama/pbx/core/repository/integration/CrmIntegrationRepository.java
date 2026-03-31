package com.dalai.llama.pbx.core.repository.integration;

import com.dalai.llama.pbx.core.domain.entity.integration.CrmIntegration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CrmIntegrationRepository extends JpaRepository<CrmIntegration, UUID> {
    List<CrmIntegration> findByTenantId(UUID tenantId);
    List<CrmIntegration> findByTenantIdAndIsActiveTrue(UUID tenantId);
}