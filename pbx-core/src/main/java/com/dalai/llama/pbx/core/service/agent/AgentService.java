package com.dalai.llama.pbx.core.service.agent;


import com.dalai.llama.pbx.core.client.TenantServiceClient;
import com.dalai.llama.pbx.core.domain.entity.core.Agent;
import com.dalai.llama.pbx.core.domain.entity.kamailio.Subscriber;
import com.dalai.llama.pbx.core.domain.enums.AgentRole;
import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import com.dalai.llama.pbx.core.domain.enums.SubscriberType;
import com.dalai.llama.pbx.core.dto.response.AgentProfileResponse;
import com.dalai.llama.pbx.core.dto.response.SipCredentialsResponse;
import com.dalai.llama.pbx.core.repository.core.AgentRepository;
import com.dalai.llama.pbx.core.repository.core.QueueMemberRepository;
import com.dalai.llama.pbx.core.repository.kamailio.SubscriberRepository;
import com.dalai.llama.pbx.core.service.cache.TenantConfigCacheService;
import com.dalai.llama.pbx.core.util.SipDigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

/**
 * Agent lifecycle management — the ONE place where agents and SIP subscribers are kept in sync.
 *
 * Invariant: Every active agent has exactly one active subscriber row with matching
 * username + domain. Creating an agent creates the subscriber. Deleting an agent
 * deactivates the subscriber. Updating agent password recomputes HA1/HA1B.
 *
 * Called by:
 *   - AgentController CRUD     → /api/v1/agents
 *   - EslEventListener         → status transitions on call events
 *   - ScheduledTasks           → auto-logout stale agents
 */
/**
 * Agent lifecycle management.
 *
 * Responsibilities:
 *   - CRUD agents + auto-sync subscriber table (agent↔subscriber invariant)
 *   - Agent creation flow: PBX-Core calls tenant-service for entitlement check + Keycloak user
 *   - SIP credential rotation: generate fresh SIP password, update subscriber HA1
 *   - /me endpoints: resolve agent from JWT keycloak_user_id, return profile + SIP creds
 *   - Status management: OFFLINE→ONLINE→ON_CALL→WRAP_UP→ONLINE
 */



@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRepository agentRepository;
    private final SubscriberRepository subscriberRepository;
    private final QueueMemberRepository queueMemberRepository;
    private final TenantServiceClient tenantServiceClient;
    private final TenantConfigCacheService configCache;

    @Value("${sip.wss-url:wss://sip.dalaillama.in:7443}")
    private String sipWssUrl;

    @Value("${coturn.host:turn.dalaillama.in}")
    private String turnHost;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ═══════════════════════════════════════════════════════════
    // CREATE — agent + Keycloak user + subscriber in one flow
    // ═══════════════════════════════════════════════════════════

    /**
     * Create an agent:
     *   1. Call tenant-service to validate entitlements + create Keycloak user
     *   2. Create agent row in PBX-Core DB
     *   3. Create subscriber row (SIP auth)
     *
     * @return created Agent with SIP password in the response (one-time display)
     */
    @Transactional
    public Map<String, Object> createAgent(UUID tenantId, UUID subscriptionId, String username,
                                           String sipDomain, String displayName, String extension,
                                           String email, AgentRole role, List<String> skills) {

        // Check uniqueness locally first
        if (agentRepository.findByTenantIdAndUsername(tenantId, username).isPresent()) {
            throw new IllegalArgumentException("Agent with username " + username + " already exists");
        }

        // Step 1: Call tenant-service for entitlement validation + Keycloak user creation
        Map<String, Object> provisionRequest = new LinkedHashMap<>();
        provisionRequest.put("tenant_id", tenantId.toString());
        provisionRequest.put("subscription_id", subscriptionId.toString());
        provisionRequest.put("username", username);
        provisionRequest.put("email", email);
        provisionRequest.put("display_name", displayName);
        provisionRequest.put("role", role != null ? role.name() : "AGENT");

        long currentCount = agentRepository.countByTenantIdAndIsActiveTrue(tenantId);
        provisionRequest.put("current_agent_count", currentCount);
        Optional<Map<String, Object>> provisionResult =
                tenantServiceClient.provisionAgent(tenantId, provisionRequest);

        if (provisionResult.isEmpty()) {
            throw new IllegalStateException("Failed to provision agent via tenant-service");
        }

        Map<String, Object> tsResponse = provisionResult.get();
        Boolean approved = (Boolean) tsResponse.get("approved");
        if (!Boolean.TRUE.equals(approved)) {
            String reason = (String) tsResponse.getOrDefault("reason", "unknown");
            throw new IllegalStateException("Agent creation denied: " + reason);
        }

        String keycloakUserId = (String) tsResponse.get("keycloak_user_id");
        String sipPassword = (String) tsResponse.get("sip_password");

        // If tenant-service didn't generate SIP password, generate one
        if (sipPassword == null || sipPassword.isBlank()) {
            sipPassword = generateSipPassword();
        }

        // Step 2: Create agent row
        Agent agent = Agent.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .username(username)
                .sipDomain(sipDomain)
                .displayName(displayName)
                .extension(extension)
                .email(email)
                .role(role != null ? role : AgentRole.AGENT)
                .keycloakUserId(keycloakUserId)
                .skills(skills != null ? skills : List.of())
                .build();
        agent = agentRepository.save(agent);

        // Step 3: Create subscriber (SIP auth)
        createOrUpdateSubscriber(tenantId, subscriptionId, username, sipDomain,
                sipPassword, displayName, SubscriberType.AGENT);

        log.info("Created agent {}@{} (keycloak={}, role={}) for tenant {}",
                username, sipDomain, keycloakUserId, role, tenantId);

        // Return agent + SIP password (one-time display)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        result.put("sip_password", sipPassword);
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // /ME — Resolve agent from JWT claims
    // ═══════════════════════════════════════════════════════════

    /**
     * Get agent profile from JWT keycloak_user_id.
     * Called by GET /api/v1/agents/me.
     */
    public Optional<AgentProfileResponse> getProfile(String keycloakUserId) {
        return agentRepository.findByKeycloakUserId(keycloakUserId)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .map(this::buildProfile);
    }

    /**
     * Get agent profile by tenant + keycloak user.
     */
    public Optional<AgentProfileResponse> getProfile(UUID tenantId, String keycloakUserId) {
        return agentRepository.findByTenantIdAndKeycloakUserId(tenantId, keycloakUserId)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .map(this::buildProfile);
    }

    private AgentProfileResponse buildProfile(Agent agent) {
        // Fetch tenant config — this is the TenantApp snapshot cached during provisioning.
        // All endpoint URLs were set during provisioning and stored on TenantApp.
        // PBX-Core's tenant config cache mirrors TenantApp fields.
        Map<String, Object> config = configCache.getConfig(agent.getTenantId()).orElse(Map.of());

        return AgentProfileResponse.builder()
                .id(agent.getId())
                .username(agent.getUsername())
                .extension(agent.getExtension())
                .displayName(agent.getDisplayName())
                .email(agent.getEmail())
                .role(agent.getRole())
                .status(agent.getStatus())
                .skills(agent.getSkills())
                .tenantId(agent.getTenantId())
                .subscriptionId(agent.getSubscriptionId())
                .sipDomain(agent.getSipDomain())
                .tenantSlug(extractString(config, "namespace", "default"))
                // Endpoints — ALL from TenantApp (set during provisioning)
                .sipWssUrl(extractString(config, "websocketUrl", sipWssUrl))
                .stompWsUrl(extractString(config, "stompWsUrl",
                        extractString(config, "websocketUrl", null)))
                .turnUrl(extractString(config, "turnUrl", "turn:" + turnHost + ":3478"))
                // Feature flags — from TenantApp entitlements
                .bargeEnabled(extractBool(config, "bargeEnabled", false))
                .whisperEnabled(extractBool(config, "whisperEnabled", false))
                .listenEnabled(extractBool(config, "listenEnabled", false))
                .recordingEnabled(extractBool(config, "recordingEnabled", false))
                .aiEnabled(extractBool(config, "aiBotEnabled", false))
                .conferenceEnabled(extractBool(config, "conferenceEnabled", false))
                .transferEnabled(extractBool(config, "blindTransferEnabled", true))
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // SIP CREDENTIALS — auto-rotate on each fetch
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate fresh SIP credentials for an agent.
     * Called by GET /api/v1/agents/me/sip-credentials.
     *
     * Generates a new random SIP password, updates the subscriber HA1/HA1B,
     * and returns the credentials. The plaintext password is returned ONCE;
     * only the HA1 hash is stored.
     *
     * This means:
     *   - Each login gets fresh SIP credentials
     *   - Previous sessions' SIP registration expires naturally (300s default)
     *   - If agent is deactivated, next credential fetch returns 403
     */
    @Transactional
    public Optional<SipCredentialsResponse> generateSipCredentials(String keycloakUserId) {
        return agentRepository.findByKeycloakUserId(keycloakUserId)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .map(agent -> {
                    String newPassword = generateSipPassword();

                    // Update subscriber HA1/HA1B with new password
                    createOrUpdateSubscriber(
                            agent.getTenantId(), agent.getSubscriptionId(),
                            agent.getUsername(), agent.getSipDomain(),
                            newPassword, agent.getDisplayName(), SubscriberType.AGENT);

                    // Read SIP WSS URL from tenant config (set during provisioning)
                    Map<String, Object> config = configCache.getConfig(agent.getTenantId()).orElse(Map.of());
                    String resolvedSipWssUrl = extractString(config, "sipWssUrl",
                            extractString(config, "sip_wss_url", sipWssUrl));

                    log.info("Rotated SIP credentials for agent {} ({})",
                            agent.getUsername(), agent.getKeycloakUserId());

                    return SipCredentialsResponse.builder()
                            .extension(agent.getExtension())
                            .sipDomain(agent.getSipDomain())
                            .sipUsername(agent.getUsername())
                            .sipPassword(newPassword)
                            .sipWssUrl(resolvedSipWssUrl)
                            .sipUri("sip:" + agent.getUsername() + "@" + agent.getSipDomain())
                            .displayName(agent.getDisplayName())
                            .build();
                });
    }

    // ═══════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Agent updateAgent(UUID agentId, String displayName, String extension,
                             String email, List<String> skills) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        if (displayName != null) agent.setDisplayName(displayName);
        if (extension != null) agent.setExtension(extension);
        if (email != null) agent.setEmail(email);
        if (skills != null) agent.setSkills(skills);

        agent = agentRepository.save(agent);

        // Update subscriber display_name if changed
        if (displayName != null) {
            subscriberRepository.findByUsernameAndDomain(agent.getUsername(), agent.getSipDomain())
                    .ifPresent(sub -> {
                        sub.setDisplayName(displayName);
                        subscriberRepository.save(sub);
                    });
        }

        log.info("Updated agent {}", agentId);
        return agent;
    }

    // ═══════════════════════════════════════════════════════════
    // DELETE (soft — deactivate agent + subscriber)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void deleteAgent(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        agent.setIsActive(false);
        agent.setStatus(AgentStatus.OFFLINE);
        agentRepository.save(agent);

        subscriberRepository.findByUsernameAndDomain(agent.getUsername(), agent.getSipDomain())
                .ifPresent(sub -> {
                    sub.setIsActive(false);
                    subscriberRepository.save(sub);
                });

        queueMemberRepository.deleteByAgentId(agentId);

        log.info("Deleted (deactivated) agent {} ({}@{})", agentId, agent.getUsername(), agent.getSipDomain());
    }

    // ═══════════════════════════════════════════════════════════
    // STATUS
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void updateStatus(UUID agentId, AgentStatus newStatus) {
        agentRepository.updateStatus(agentId, newStatus);
        log.debug("Agent {} → {}", agentId, newStatus);
    }

    // ═══════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════

    public Optional<Agent> getById(UUID agentId) {
        return agentRepository.findById(agentId);
    }

    public List<Agent> getByTenantId(UUID tenantId) {
        return agentRepository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    public List<Agent> getAvailableAgents(UUID tenantId) {
        return agentRepository.findAvailableAgents(tenantId);
    }

    public List<Agent> getByStatus(UUID tenantId, AgentStatus status) {
        return agentRepository.findByTenantIdAndStatus(tenantId, status);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════

    private void createOrUpdateSubscriber(UUID tenantId, UUID subscriptionId,
                                          String username, String domain,
                                          String password, String displayName,
                                          SubscriberType type) {
        String ha1 = SipDigestUtil.ha1(username, domain, password);
        String ha1b = SipDigestUtil.ha1b(username, domain, password);

        Subscriber subscriber = subscriberRepository.findByUsernameAndDomain(username, domain)
                .orElse(Subscriber.builder()
                        .username(username)
                        .domain(domain)
                        .tenantId(tenantId)
                        .subscriptionId(subscriptionId)
                        .subscriberType(type)
                        .build());

        subscriber.setHa1(ha1);
        subscriber.setHa1b(ha1b);
        subscriber.setDisplayName(displayName);
        subscriber.setIsActive(true);
        subscriberRepository.save(subscriber);
    }

    /**
     * Generate a secure random SIP password.
     * 16 chars, alphanumeric — strong enough for SIP digest auth.
     */
    private String generateSipPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String extractString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) v = m.get(toSnakeCase(key));
        return v != null ? v.toString() : def;
    }

    private boolean extractBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) v = m.get(toSnakeCase(key));
        return v instanceof Boolean b ? b : def;
    }

    private String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}