package com.dalai.llama.pbx.core.repository.core;

import com.dalai.llama.pbx.core.domain.entity.core.RoutingPolicy;
import com.dalai.llama.pbx.core.domain.enums.RoutingMatchType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Routing policies — inbound call routing rules.
 *
 * Evaluated by CallAuthorizationService in priority DESC order.
 * First matching policy wins and determines the routing target
 * (QUEUE, AGENT, IVR, AI_BOT, VOICEMAIL, EXTERNAL).
 *
 * If no policy matches, CallAuthorizationService falls back to
 * product-code-based default routing.
 */
@Repository
public interface RoutingPolicyRepository extends JpaRepository<RoutingPolicy, UUID> {

    /**
     * THE runtime query — called on every inbound call.
     * Ordered by priority DESC so highest priority rule is evaluated first.
     */
    List<RoutingPolicy> findByTenantIdAndIsActiveTrueOrderByPriorityDesc(UUID tenantId);

    /**
     * Specific match — e.g., find all DID-based rules for a tenant.
     * Used by RoutingController for CRUD listing.
     */
    List<RoutingPolicy> findByTenantIdAndMatchType(UUID tenantId, RoutingMatchType matchType);

    /**
     * Find policy matching a specific DID or caller ID.
     * Alternative to loading all policies and filtering in Java.
     */
    List<RoutingPolicy> findByTenantIdAndMatchTypeAndMatchValueAndIsActiveTrue(
            UUID tenantId, RoutingMatchType matchType, String matchValue);

    List<RoutingPolicy> findByTenantId(UUID tenantId);

    long countByTenantId(UUID tenantId);
}
