package com.dalai.llama.pbx.core.controller.agent;



import com.dalai.llama.pbx.core.domain.entity.core.Agent;
import com.dalai.llama.pbx.core.domain.enums.AgentRole;
import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import com.dalai.llama.pbx.core.dto.response.AgentProfileResponse;
import com.dalai.llama.pbx.core.dto.response.SipCredentialsResponse;
import com.dalai.llama.pbx.core.service.agent.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent API — CRUD + /me (self-service) + SIP credential management.
 *
 * Endpoint groups:
 *
 *   /me endpoints (any authenticated agent/supervisor/admin):
 *     GET  /agents/me                  → agent profile from JWT
 *     GET  /agents/me/sip-credentials  → fresh SIP password for SIP.js registration
 *
 *   CRUD endpoints (TENANT_ADMIN only):
 *     POST /agents                     → create agent (calls tenant-service for Keycloak)
 *     PUT  /agents/{id}               → update agent
 *     PUT  /agents/{id}/status        → update status (also used by agents themselves)
 *     DELETE /agents/{id}             → deactivate agent
 *
 *   Query endpoints (SUPERVISOR, TENANT_ADMIN):
 *     GET  /agents                     → list agents for tenant
 *     GET  /agents/{id}               → get single agent
 *     GET  /agents/available           → available agents (for queue routing UI)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    // ═══════════════════════════════════════════════════════════
    // /ME — Self-service (any authenticated user with SIP needs)
    // ═══════════════════════════════════════════════════════════

    /**
     * Get current agent's profile from JWT.
     *
     * Agent UI calls this at bootstrap to get:
     *   - extension, sipDomain (for SIP.js configuration)
     *   - tenantId, tenantSlug (for STOMP topic subscription)
     *   - feature flags (what UI features to show)
     *   - STOMP/SIP/TURN endpoint URLs
     *
     * JWT must contain "sub" (Keycloak user ID) claim.
     */
    @GetMapping("/me")
    public ResponseEntity<AgentProfileResponse> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();

        return agentService.getProfile(keycloakUserId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get fresh SIP credentials for SIP.js registration.
     *
     * Agent UI calls this at bootstrap (after /me) to get:
     *   - sipUsername, sipDomain, sipPassword (for SIP.js UserAgent)
     *   - sipWssUrl (Kamailio WebSocket endpoint)
     *   - sipUri (full SIP URI)
     *
     * Each call generates a NEW password and updates the subscriber HA1.
     * This means only the most recent session can authenticate.
     * Previous sessions will fail on next re-REGISTER (300s default expiry).
     */
    @GetMapping("/me/sip-credentials")
    public ResponseEntity<SipCredentialsResponse> getMySipCredentials(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();

        return agentService.generateSipCredentials(keycloakUserId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    // CREATE — TENANT_ADMIN only
    // ═══════════════════════════════════════════════════════════

    /**
     * Create an agent.
     *
     * Flow:
     *   1. PBX-Core calls tenant-service to validate entitlement + create Keycloak user
     *   2. PBX-Core creates agent + subscriber rows
     *   3. Returns agent + SIP password (one-time display for admin to share)
     *
     * Admin creates agents, supervisors, or other admins.
     * Role hierarchy: TENANT_ADMIN > SUPERVISOR > AGENT
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = agentService.createAgent(
                UUID.fromString(body.get("tenant_id").toString()),
                UUID.fromString(body.get("subscription_id").toString()),
                (String) body.get("username"),
                (String) body.get("sip_domain"),
                (String) body.get("display_name"),
                (String) body.get("extension"),
                (String) body.get("email"),
                body.get("role") != null ? AgentRole.valueOf(body.get("role").toString().toUpperCase()) : AgentRole.AGENT,
                body.get("skills") instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of()
        );
        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════

    @PutMapping("/{id}")
    public ResponseEntity<Agent> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> skills = body.get("skills") instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : null;
        Agent agent = agentService.updateAgent(id,
                (String) body.get("display_name"),
                (String) body.get("extension"),
                (String) body.get("email"),
                skills
        );
        return ResponseEntity.ok(agent);
    }

    /**
     * Update agent status.
     * Agents can set their own status (ONLINE, BREAK, OFFLINE).
     * System sets ON_CALL, WRAP_UP via EslEventListener.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        agentService.updateStatus(id, AgentStatus.valueOf(body.get("status").toUpperCase()));
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        agentService.deleteAgent(id);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════
    // QUERY
    // ═══════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<List<Agent>> list(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(agentService.getByTenantId(tenant_id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Agent> get(@PathVariable UUID id) {
        return agentService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/available")
    public ResponseEntity<List<Agent>> available(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(agentService.getAvailableAgents(tenant_id));
    }


}