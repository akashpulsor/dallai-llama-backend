package com.dalai.llama.tenant.repository;


import com.dalai.llama.tenant.domain.entity.TenantStateAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantStateAuditRepository
        extends JpaRepository<TenantStateAudit, UUID> {
}
