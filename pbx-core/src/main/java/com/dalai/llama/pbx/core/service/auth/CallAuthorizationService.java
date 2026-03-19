package com.dalai.llama.pbx.core.service.auth;


import com.dalai.llama.pbx.core.client.ProductServiceClient;
import com.dalai.llama.pbx.core.domain.entity.core.RoutingPolicy;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.repository.campaign.DncEntryRepository;
import com.dalai.llama.pbx.core.repository.core.RoutingPolicyRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Call authorization — the single most critical runtime path in PBX-Core.
 *
 * Called by Kamailio http_client on EVERY inbound/outbound INVITE:
 *   POST /internal/kamailio/authorize/inbound
 *   POST /internal/kamailio/authorize/outbound
 *
 * Must respond within ~3 seconds (Kamailio's http_client timeout).
 *
 * Inbound check order (fail-fast):
 *   1. Resolve tenant by DID (TenantConfigCacheService — Redis/DB/HTTP)
 *   2. Tenant status check (ACTIVE? not SUSPENDED/DEPROVISIONED?)
 *   3. Subscription validity (ProductServiceClient — billing ok?)
 *   4. Channel limit (ChannelCounterService — Redis atomic read)
 *   5. Routing resolution (RoutingPolicyRepository — DB query, cached after first call)
 *
 * Outbound additional checks:
 *   6. DNC list (DncEntryRepository — reject if number is on DNC)
 *   7. Balance check (ProductServiceClient — sufficient credits?)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallAuthorizationService {

    private final TenantConfigCacheService configCache;
    private final ChannelCounterService channelCounter;
    private final ProductServiceClient productServiceClient;
    private final DncEntryRepository dncRepository;
    private final RoutingPolicyRepository routingRepository;

    // ═══════════════════════════════════════════════════════════
    // INBOUND AUTHORIZATION
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> authorizeInbound(String didNumber, String callerNumber,
                                                String callId, String domain) {
        log.info("Auth inbound: DID={}, caller={}, callId={}", didNumber, callerNumber, callId);

        // 1. Resolve tenant by DID
        Optional<Map<String, Object>> configOpt = configCache.getConfigByDid(didNumber);
        if (configOpt.isEmpty()) {
            log.warn("No tenant for DID {}", didNumber);
            return denied("NO_TENANT", "No tenant found for DID " + didNumber);
        }

        Map<String, Object> config = configOpt.get();
        UUID tenantId = extractUuid(config, "tenantId");
        UUID subscriptionId = extractUuid(config, "subscriptionId");
        String productCode = extractString(config, "productCode", "BASIC_PBX");

        // 2. Tenant status
        String deploymentStatus = extractString(config, "deploymentStatus", "ACTIVE");
        if (!isActiveStatus(deploymentStatus)) {
            log.warn("Tenant {} is {} — rejecting", tenantId, deploymentStatus);
            return denied("TENANT_INACTIVE", "Tenant is " + deploymentStatus);
        }

        // 3. Subscription validity (quick HTTP check, non-blocking on failure)
        if (subscriptionId != null && !isSubscriptionValid(subscriptionId)) {
            return denied("SUBSCRIPTION_INVALID", "Subscription suspended or cancelled");
        }

        // 4. Channel limit
        int maxChannels = extractInt(config, "maxChannels", 30);
        Integer maxInbound = extractIntOrNull(config, "channelInbound");
        long currentTotal = channelCounter.getTotal(tenantId);
        long currentInbound = channelCounter.getInbound(tenantId);

        if (currentTotal >= maxChannels) {
            log.warn("Tenant {} total channel limit ({}/{})", tenantId, currentTotal, maxChannels);
            return denied("CHANNEL_LIMIT", "Total channel limit exceeded");
        }
        if (maxInbound != null && currentInbound >= maxInbound) {
            log.warn("Tenant {} inbound channel limit ({}/{})", tenantId, currentInbound, maxInbound);
            return denied("CHANNEL_LIMIT", "Inbound channel limit exceeded");
        }

        // 5. Resolve routing target
        RoutingResult routing = resolveInboundRouting(tenantId, didNumber, callerNumber, productCode);

        // Build success response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", true);
        result.put("tenantId", tenantId.toString());
        result.put("subscriptionId", subscriptionId != null ? subscriptionId.toString() : null);
        result.put("productCode", productCode);
        result.put("routingTarget", routing.target);
        result.put("routingType", routing.type);
        result.put("dispatcherSet", extractInt(config, "dispatcherSetId", 1));
        result.put("namespace", extractString(config, "namespace", "default"));
        result.put("sipDomain", extractString(config, "sipEndpointDomain", domain));
        result.put("aiEnabled", extractBool(config, "aiBotEnabled", false));
        result.put("recordingEnabled", extractBool(config, "recordingEnabled", false));

        log.info("Auth GRANTED inbound: DID={} tenant={} route={}:{}", didNumber, tenantId, routing.type, routing.target);
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // OUTBOUND AUTHORIZATION
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> authorizeOutbound(String callerNumber, String destination,
                                                 UUID tenantId) {
        log.info("Auth outbound: caller={}, dest={}, tenant={}", callerNumber, destination, tenantId);

        // 1. Resolve tenant config
        Optional<Map<String, Object>> configOpt = configCache.getConfig(tenantId);
        if (configOpt.isEmpty()) {
            return denied("NO_TENANT", "Tenant not found");
        }

        Map<String, Object> config = configOpt.get();
        UUID subscriptionId = extractUuid(config, "subscriptionId");

        // 2. Tenant status
        String status = extractString(config, "deploymentStatus", "ACTIVE");
        if (!isActiveStatus(status)) {
            return denied("TENANT_INACTIVE", "Tenant is " + status);
        }

        // 3. DNC check
        if (dncRepository.isOnDncList(tenantId, destination, Instant.now())) {
            log.warn("Outbound to {} blocked — DNC list, tenant={}", destination, tenantId);
            return denied("DNC_BLOCKED", "Number is on Do-Not-Call list");
        }

        // 4. Channel limit
        int maxChannels = extractInt(config, "maxChannels", 30);
        Integer maxOutbound = extractIntOrNull(config, "channelOutbound");
        long currentTotal = channelCounter.getTotal(tenantId);
        long currentOutbound = channelCounter.getOutbound(tenantId);

        if (currentTotal >= maxChannels) {
            return denied("CHANNEL_LIMIT", "Total channel limit exceeded");
        }
        if (maxOutbound != null && currentOutbound >= maxOutbound) {
            return denied("CHANNEL_LIMIT", "Outbound channel limit exceeded");
        }

        // 5. Balance check (non-blocking on failure — allow call if billing is down)
        if (subscriptionId != null) {
            Optional<Map<String, Object>> balance = productServiceClient.checkBalance(tenantId);
            if (balance.isPresent()) {
                Object bal = balance.get().get("balance");
                if (bal instanceof Number n && n.doubleValue() <= 0) {
                    return denied("INSUFFICIENT_BALANCE", "No balance remaining");
                }
            }
            // If balance check fails (product-service down), we allow the call
            // — better to let a call through than block revenue over a transient failure.
        }

        // Build success response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", true);
        result.put("tenantId", tenantId.toString());
        result.put("subscriptionId", subscriptionId != null ? subscriptionId.toString() : null);
        result.put("callerIdOverride", extractString(config, "outboundCallerId", callerNumber));
        result.put("productCode", extractString(config, "productCode", "BASIC_PBX"));

        log.info("Auth GRANTED outbound: tenant={} dest={}", tenantId, destination);
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // ROUTING RESOLUTION
    // ═══════════════════════════════════════════════════════════

    private RoutingResult resolveInboundRouting(UUID tenantId, String didNumber,
                                                String callerNumber, String productCode) {
        // Check routing policies — ordered by priority DESC, first match wins
        List<RoutingPolicy> policies = routingRepository
                .findByTenantIdAndIsActiveTrueOrderByPriorityDesc(tenantId);

        for (RoutingPolicy policy : policies) {
            if (matches(policy, didNumber, callerNumber)) {
                return new RoutingResult(
                        policy.getActionType().name(),
                        policy.getActionTarget()
                );
            }
        }

        // No policy matched — fall back to product-code default
        return defaultRoutingForProduct(productCode);
    }

    private boolean matches(RoutingPolicy policy, String didNumber, String callerNumber) {
        return switch (policy.getMatchType()) {
            case DID -> didNumber != null && didNumber.equals(policy.getMatchValue());
            case CALLER_ID -> callerNumber != null && callerNumber.equals(policy.getMatchValue());
            case TIME -> isWithinTimeCondition(policy.getTimeCondition());
            case ALL -> true;
        };
    }

    private boolean isWithinTimeCondition(Map<String, Object> timeCondition) {
        if (timeCondition == null || timeCondition.isEmpty()) return true;

        try {
            @SuppressWarnings("unchecked")
            List<Integer> days = (List<Integer>) timeCondition.get("days");
            String startStr = (String) timeCondition.get("start");
            String endStr = (String) timeCondition.get("end");

            if (days == null || startStr == null || endStr == null) return true;

            java.time.LocalDateTime now = java.time.LocalDateTime.now(
                    java.time.ZoneId.of("Asia/Kolkata")); // Default timezone
            int todayIso = now.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
            java.time.LocalTime currentTime = now.toLocalTime();
            java.time.LocalTime start = java.time.LocalTime.parse(startStr);
            java.time.LocalTime end = java.time.LocalTime.parse(endStr);

            return days.contains(todayIso) && !currentTime.isBefore(start) && currentTime.isBefore(end);
        } catch (Exception e) {
            log.warn("Failed to parse time condition: {}", e.getMessage());
            return true; // On parse failure, treat as match
        }
    }

    private RoutingResult defaultRoutingForProduct(String productCode) {
        return switch (productCode) {
            case "CONVERSATIONAL_IVR" -> new RoutingResult("AI_BOT", "default");
            case "AI_CONTACT_CENTER" -> new RoutingResult("QUEUE", "default");
            case "OUTBOUND_DIALER" -> new RoutingResult("AI_BOT", "dialer");
            case "VIRTUAL_RECEPTIONIST" -> new RoutingResult("AI_BOT", "receptionist");
            default -> new RoutingResult("QUEUE", "default"); // BASIC_PBX
        };
    }

    // ═══════════════════════════════════════════════════════════
    // SUBSCRIPTION CHECK
    // ═══════════════════════════════════════════════════════════

    private boolean isSubscriptionValid(UUID subscriptionId) {
        try {
            Optional<Map<String, Object>> subStatus = productServiceClient.getSubscriptionStatus(subscriptionId);
            if (subStatus.isPresent()) {
                String state = String.valueOf(subStatus.get().getOrDefault("status", "ACTIVE"));
                if ("SUSPENDED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                    log.warn("Subscription {} is {}", subscriptionId, state);
                    return false;
                }
            }
            // If product-service is unreachable, allow the call (fail-open)
            return true;
        } catch (Exception e) {
            log.warn("Subscription check failed for {} — allowing call: {}", subscriptionId, e.getMessage());
            return true; // Fail-open: don't block calls because billing service is down
        }
    }

    private boolean isActiveStatus(String status) {
        return "ACTIVE".equalsIgnoreCase(status)
                || "DEPLOYED".equalsIgnoreCase(status)
                || "COMPLETED".equalsIgnoreCase(status); // ProvisioningTaskStatus.COMPLETED
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private Map<String, Object> denied(String code, String reason) {
        return Map.of("allowed", false, "code", code, "reason", reason);
    }

    private record RoutingResult(String type, String target) {}

    private UUID extractUuid(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    private String extractString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private int extractInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private Integer extractIntOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    private boolean extractBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }
}