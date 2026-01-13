package com.dalai.llama.agent.controller;

import com.dalai.llama.agent.dto.*;
import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.exception.SipAuthException;
import com.dalai.llama.agent.service.AgentService;
import com.dalai.llama.agent.sip.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    private final PbxCoreClient pbxCoreClient;

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_agent:read')")
    public ResponseEntity<List<Agent>> listAgents(@RequestParam String tenantId) {
        return ResponseEntity.ok(agentService.findByTenant(tenantId));
    }

    @PostMapping("/sip/auth")
    public ResponseEntity<Void> authenticateSipAgent(@RequestBody SipAuthRequest request) {
        try {
            // Service method verifies credentials using the Digest formula
            agentService.authenticateSipAgentDigest(request);
            return ResponseEntity.ok().build();
        } catch (SipAuthException e) {
            // Log authentication failure
            System.err.println("SIP Auth Failure: " + e.getMessage());
            return ResponseEntity.status(401).build(); // Unauthorized
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_agent:read')")
    public ResponseEntity<Agent> getAgent(@PathVariable Long id) {
        return agentService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_agent:write')")
    public ResponseEntity<Agent> createAgent(@RequestBody Agent agent) {
        return ResponseEntity.ok(agentService.createAgent(agent));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_agent:write')")
    public ResponseEntity<Agent> updateAgent(@PathVariable Long id, @RequestBody Agent agent) {
        agent.setId(id);
        return ResponseEntity.ok(agentService.updateAgent(agent));
    }

    @PutMapping("/register")
    @PreAuthorize("hasAuthority('SCOPE_agent:write')")
    public ResponseEntity<Agent> configureAgent(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody AgentConfigurationRequest configRequest) {
        String externalId = jwt.getSubject(); // Keycloak user ID is typically the JWT subject
        Agent configuredAgent = agentService.configureAgent(externalId, configRequest);
        return ResponseEntity.ok(configuredAgent);
    }


    @PostMapping("/{id}/availability")
    @PreAuthorize("hasAuthority('SCOPE_agent:write')")
    public ResponseEntity<Void> setAvailability(
            @PathVariable Long id,
            @RequestParam Agent.AvailabilityStatus status) {
        agentService.setAvailability(id, status);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/available")
    @PreAuthorize("hasAuthority('SCOPE_agent:read')")
    public ResponseEntity<List<Agent>> getAvailableAgents(@RequestParam String tenantId) {
        return ResponseEntity.ok(agentService.findAvailableAgents(tenantId));
    }

    /**
     * Unified endpoint to toggle agent SIP presence.
     *
     * POST /api/v1/tenants/{tenantId}/agents/{agentId}/presence?online=true
     * POST /api/v1/tenants/{tenantId}/agents/{agentId}/presence?online=false
     */
    @PostMapping("/{agentId}/presence")
    @PreAuthorize("hasAuthority('SCOPE_agent:write')")
    public ResponseEntity<?> updatePresence(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @RequestParam boolean online) {

        // validate agent
        Agent agent = agentService.findByTenantIdAndId(tenantId, agentId);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        // fetch signaling config (realm, wssUrl, etc.)
        SignalingConfigResponse signaling = pbxCoreClient.getSignalingConfig(tenantId);
        if (signaling == null) {
            return ResponseEntity.status(500).body("Signaling config not found for tenant");
        }

        String realm = signaling.getAuthRealm();
        String sipUrl = signaling.getWssUrl();

        if (online) {
            // mark online (DB + Redis inside service)
            agentService.setOnlineStatus(agentId, true, realm, sipUrl);

            // return SIP bootstrap info for the browser
            SipBootstrapResponse response = SipBootstrapResponse.builder()
                    .sipUrl(sipUrl)
                    .sipUsername(agent.getUsername())
                    .sipPassword(agent.getPassword())
                    .realm(realm)
                    .displayName(agent.getDisplayName())
                    .build();

            return ResponseEntity.ok(response);
        } else {
            // offline (DB + Redis inside service)
            agentService.setOnlineStatus(agentId, false, realm, sipUrl);
            return ResponseEntity.ok().build();
        }
    }

    @PostMapping("/{agentId}/outbound")
    public ResponseEntity<?> callOutbound(
            @PathVariable String tenantId,
            @PathVariable Long agentId,
            @RequestBody OutboundCallUIRequest req) {

        Map<String,Object> result = agentService.startOutboundCall(tenantId, agentId, req);
        return ResponseEntity.ok(result);
    }
}
