package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.Cdr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CdrRepository extends JpaRepository<Cdr, UUID> {

    boolean existsByCallIdAndTenantId(String callId, UUID tenantId);

    Optional<Cdr> findByCallIdAndTenantId(String callId, UUID tenantId);
}
