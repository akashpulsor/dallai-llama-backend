package com.dalai.llama.pbx.core.repository;

import com.dalai.llama.pbx.core.model.RoutingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoutingPolicyRepository extends JpaRepository<RoutingPolicy, String> {
    Optional<RoutingPolicy> findByTenantIdAndEntrypoint(String tenantId, String entrypoint);
}
