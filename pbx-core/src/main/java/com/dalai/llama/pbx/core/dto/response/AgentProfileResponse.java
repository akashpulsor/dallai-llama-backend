package com.dalai.llama.pbx.core.dto.response;


import com.dalai.llama.pbx.core.domain.enums.AgentRole;
import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Agent profile returned by GET /api/v1/agents/me.
 *
 * Contains everything the Agent UI needs at bootstrap:
 *   - Identity (name, extension, role)
 *   - Tenant context (tenantId, sipDomain, tenantSlug)
 *   - WebSocket endpoints
 *   - Current status
 *   - Feature flags (what this agent's plan allows)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentProfileResponse {

    // ── Identity ──
    private UUID id;
    private String username;
    private String extension;
    private String displayName;
    private String email;
    private AgentRole role;
    private AgentStatus status;
    private List<String> skills;

    // ── Tenant context ──
    private UUID tenantId;
    private UUID subscriptionId;
    private String sipDomain;
    private String tenantSlug;          // namespace / slug for URL construction

    // ── Endpoints (Agent UI uses these to connect) ──
    private String sipWssUrl;           // "wss://sip.dalaillama.in:7443"
    private String stompWsUrl;          // "wss://agent-acme.dalaillama.in/ws"
    private String turnUrl;             // "turn:turn.dalaillama.in:3478"

    // ── Feature flags (from tenant config / entitlements) ──
    private Boolean bargeEnabled;
    private Boolean whisperEnabled;
    private Boolean listenEnabled;
    private Boolean recordingEnabled;
    private Boolean aiEnabled;
    private Boolean conferenceEnabled;
    private Boolean transferEnabled;
}