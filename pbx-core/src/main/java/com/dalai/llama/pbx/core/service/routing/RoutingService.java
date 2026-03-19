package com.dalai.llama.pbx.core.service.routing;


import com.dalai.llama.pbx.core.domain.entity.core.IvrFlow;
import com.dalai.llama.pbx.core.domain.entity.core.RoutingPolicy;
import com.dalai.llama.pbx.core.domain.enums.RoutingActionType;
import com.dalai.llama.pbx.core.domain.enums.RoutingMatchType;
import com.dalai.llama.pbx.core.repository.core.IvrFlowRepository;
import com.dalai.llama.pbx.core.repository.core.RoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Routing policy and IVR flow management.
 *
 * Routing policies are evaluated at runtime by CallAuthorizationService
 * (priority DESC, first match wins). This service handles CRUD only —
 * the runtime evaluation logic lives in CallAuthorizationService.
 *
 * IVR flows are stored as JSONB (visual IVR builder output) and
 * referenced by routing policies with action_type = IVR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final RoutingPolicyRepository policyRepository;
    private final IvrFlowRepository ivrFlowRepository;

    // ═══════════════════════════════════════════════════════════
    // ROUTING POLICIES
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public RoutingPolicy createPolicy(UUID tenantId, UUID subscriptionId, String name,
                                      RoutingMatchType matchType, String matchValue,
                                      RoutingActionType actionType, String actionTarget,
                                      Integer priority, Map<String, Object> timeCondition) {

        RoutingPolicy policy = RoutingPolicy.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .name(name)
                .matchType(matchType != null ? matchType : RoutingMatchType.ALL)
                .matchValue(matchValue)
                .actionType(actionType)
                .actionTarget(actionTarget)
                .priority(priority != null ? priority : 0)
                .timeCondition(timeCondition)
                .build();

        policy = policyRepository.save(policy);
        log.info("Created routing policy '{}' for tenant {} ({}:{} → {}:{})",
                name, tenantId, matchType, matchValue, actionType, actionTarget);
        return policy;
    }

    @Transactional
    public RoutingPolicy updatePolicy(UUID policyId, String name, RoutingMatchType matchType,
                                      String matchValue, RoutingActionType actionType,
                                      String actionTarget, Integer priority,
                                      Map<String, Object> timeCondition, Boolean isActive) {

        RoutingPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + policyId));

        if (name != null) policy.setName(name);
        if (matchType != null) policy.setMatchType(matchType);
        if (matchValue != null) policy.setMatchValue(matchValue);
        if (actionType != null) policy.setActionType(actionType);
        if (actionTarget != null) policy.setActionTarget(actionTarget);
        if (priority != null) policy.setPriority(priority);
        if (timeCondition != null) policy.setTimeCondition(timeCondition);
        if (isActive != null) policy.setIsActive(isActive);

        return policyRepository.save(policy);
    }

    @Transactional
    public void deletePolicy(UUID policyId) {
        policyRepository.deleteById(policyId);
        log.info("Deleted routing policy {}", policyId);
    }

    public List<RoutingPolicy> getPolicies(UUID tenantId) {
        return policyRepository.findByTenantId(tenantId);
    }

    public List<RoutingPolicy> getActivePolicies(UUID tenantId) {
        return policyRepository.findByTenantIdAndIsActiveTrueOrderByPriorityDesc(tenantId);
    }

    // ═══════════════════════════════════════════════════════════
    // IVR FLOWS
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public IvrFlow createIvrFlow(UUID tenantId, UUID subscriptionId, String name,
                                 Map<String, Object> flowJson) {

        IvrFlow flow = IvrFlow.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .name(name)
                .flowJson(flowJson)
                .build();

        flow = ivrFlowRepository.save(flow);
        log.info("Created IVR flow '{}' for tenant {}", name, tenantId);
        return flow;
    }

    @Transactional
    public IvrFlow updateIvrFlow(UUID flowId, String name, Map<String, Object> flowJson, Boolean isActive) {
        IvrFlow flow = ivrFlowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("IVR flow not found: " + flowId));

        if (name != null) flow.setName(name);
        if (flowJson != null) flow.setFlowJson(flowJson);
        if (isActive != null) flow.setIsActive(isActive);

        return ivrFlowRepository.save(flow);
    }

    public List<IvrFlow> getIvrFlows(UUID tenantId) {
        return ivrFlowRepository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    public Optional<IvrFlow> getIvrFlow(UUID flowId) {
        return ivrFlowRepository.findById(flowId);
    }
}