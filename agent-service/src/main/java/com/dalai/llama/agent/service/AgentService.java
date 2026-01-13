package com.dalai.llama.agent.service;

import com.dalai.llama.agent.dto.OutboundCallRequest;
import com.dalai.llama.agent.dto.OutboundCallUIRequest;
import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.events.AgentEventsProducer;
import com.dalai.llama.agent.exception.AgentNotFoundException;
import com.dalai.llama.agent.exception.SipAuthException;
import com.dalai.llama.agent.repository.AgentRepository;
import com.dalai.llama.agent.repository.CallSessionRepository;
import com.dalai.llama.agent.sip.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dalai.llama.agent.dto.AgentConfigurationRequest;
import com.dalai.llama.agent.dto.SipAuthRequest; // Import the new DTO


@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {
    
    private final AgentRepository agentRepository;
    private final CallSessionRepository callSessionRepository;
    private final AgentEventsProducer eventsProducer;
    private final AgentEventLogService eventLogService;
    private final PresenceStore presenceStore;
    private final PbxCoreClient pbxCoreClient;

    @Transactional
    public Agent createAgent(Agent agent) {
        log.info("Creating agent: {} for tenant: {}", agent.getUsername(), agent.getTenantId());
        Agent saved = agentRepository.save(agent);
        eventsProducer.publishAgentEvent(saved.getTenantId(), "AGENT_CREATED", saved);
        eventLogService.logEvent(saved.getId(), null, "AGENT_CREATED", "Agent created", null);
        return saved;
    }

    @Transactional
    public Agent updateAgent(Agent agent) {
        log.info("Updating agent: {} (ID: {})", agent.getUsername(), agent.getId());
        Agent saved = agentRepository.save(agent);
        eventsProducer.publishAgentEvent(saved.getTenantId(), "AGENT_UPDATED", saved);
        return saved;
    }

    public List<Agent> findByTenant(String tenantId) {
        return agentRepository.findByTenantId(tenantId);
    }

    public Optional<Agent> findById(Long id) {
        return agentRepository.findById(id);
    }

    public Agent findByTenantIdAndId(String tenantId, Long id) {
        return agentRepository.findByTenantIdAndId(tenantId,id).orElseThrow(() ->
            new AgentNotFoundException("Agent not found with ID: " + id + " for tenant: " + tenantId));
    }

    public Optional<Agent> findByExternalId(String externalId) {
        return agentRepository.findByExternalId(externalId);
    }

    public List<Agent> findAvailableAgents(String tenantId) {
        return agentRepository.findAvailableAgents(tenantId);
    }

    public List<Agent> findOnlineAgents(String tenantId) {
        return agentRepository.findOnlineAgents(tenantId);
    }

    /**
     * Previously setOnlineStatus wrote everything to DB and redis.
     * New behavior:
     *  - Persist historical change to DB
     *  - Update presenceStore desired state and (if going offline) sipRegistered cleared
     */
    @Transactional
    public void setOnlineStatus(Long agentId, boolean online, String realm, String sipUrl) {
        agentRepository.findById(agentId).ifPresent(agent -> {

            // Persist for history / audit
            agent.setLastSeenAt(OffsetDateTime.now());
            agent.setOnline(online);
            agent.setStatus(online ? Agent.AgentStatus.ONLINE : Agent.AgentStatus.OFFLINE);
            if (!online) {
                agent.setAvailable(false);
            }
            agentRepository.save(agent);

            // publish analytic events & logs
            eventsProducer.publishAgentEvent(agent.getTenantId(), "AGENT_STATUS_CHANGED", agent);
            eventLogService.logEvent(agentId, null, "STATUS_CHANGE",
                    "Agent status changed to " + (online ? "ONLINE" : "OFFLINE"), null);

            // Update Redis presence desired state and (if online) provide realm/sipUrl for bootstrap
            if (online) {
                presenceStore.setOnline(agent.getTenantId(),
                        agentId,
                        agent.getUsername(),
                        realm,
                        sipUrl,
                        agent.getAvailabilityStatus() == null ? "AVAILABLE" : agent.getAvailabilityStatus().name());
            } else {
                presenceStore.setOffline(agent.getTenantId(), agentId);
            }
        });
    }

    @Transactional
    public void setAvailability(Long agentId, Agent.AvailabilityStatus availabilityStatus) {
        agentRepository.findById(agentId).ifPresent(agent -> {

            // Persist for history
            agent.setAvailabilityStatus(availabilityStatus);
            agent.setAvailable(availabilityStatus == Agent.AvailabilityStatus.AVAILABLE);
            agentRepository.save(agent);

            // Publish events/log
            eventsProducer.publishAgentEvent(agent.getTenantId(), "AGENT_AVAILABILITY_CHANGED", agent);
            eventLogService.logEvent(agentId, null, "AVAILABILITY_CHANGE",
                    "Availability changed to " + availabilityStatus, null);

            // Update desired availability in presence store (routing will compute effectiveAvailability)
            presenceStore.updateAvailability(agent.getTenantId(), agentId, availabilityStatus);
        });
    }

    public Map<String, Object> startOutboundCall(
            String tenantId,
            Long agentId,
            OutboundCallUIRequest req) {

        // 1️⃣ Load agent from database
        Agent agent = agentRepository.findByTenantIdAndId(tenantId, agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found"));

        // 2️⃣ Fetch SIP information from Redis presence
        Map<String,String> presence = presenceStore.getPresence(tenantId, agentId);

        String agentContact  = presence.get("contact");
        String agentUsername = presence.get("username");
        String sipRealm      = presence.get("realm");

        if (agentContact == null || agentContact.isBlank()) {
            throw new RuntimeException("Agent is not registered (no SIP contact)");
        }

        // 3️⃣ Mark agent BUSY immediately
        presenceStore.setDesiredAvailability(tenantId, agentId, "BUSY");

        // 4️⃣ Get dynamic Kamailio RPC URL (injected at provisioning time)
        String kamailioRpcUrl = System.getenv("KAMAILIO_RPC_URL");
        if (kamailioRpcUrl == null) {
            throw new RuntimeException("RPC URL missing in environment");
        }

        // 5️⃣ Build request to PBX-Core
        OutboundCallRequest outbound = OutboundCallRequest.builder()
                .tenantId(tenantId)
                .agentId(agentId.toString())
                .agentUsername(agentUsername)
                .agentContact(agentContact)
                .from(req.getFrom() == null ? agent.getExtension() : req.getFrom())
                .to(req.getTo())
                .sdpOffer(req.getSdpOffer())
                .kamailioRpcUrl(kamailioRpcUrl)
                .build();

        // 6️⃣ Call PBX-Core
        return pbxCoreClient.startOutboundCall(outbound);
    }

    @Transactional
    public void updateLastSeen(Long agentId) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.updateLastSeen();
            agentRepository.save(agent);
            presenceStore.updateLastSeen(agent.getTenantId(), agentId);
        });
    }

    public boolean canAcceptCall(Long agentId) {
        Optional<Agent> agentOpt = agentRepository.findById(agentId);
        if (agentOpt.isEmpty()) {
            return false;
        }
        
        Agent agent = agentOpt.get();
        if (!agent.isAvailableForCall()) {
            return false;
        }
        
        long activeCallsCount = callSessionRepository.countActiveCallsByAgent(agentId);
        return activeCallsCount < agent.getMaxConcurrentCalls();
    }

    @Transactional
    public void incrementCallStats(Long agentId, long durationSeconds) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.incrementCallStats(durationSeconds);
            agentRepository.save(agent);
        });
    }

    // --- Utility for MD5 Hashing (Required for Digest Auth) ---
    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Should not happen in modern JVMs
            throw new RuntimeException("MD5 algorithm not found for SIP Digest Auth.", e);
        }
    }

    /**
     * Authenticates an agent using SIP Digest mechanism (called by Kamailio).
     * @param request DTO containing digest components (username, nonce, client response, etc.).
     * @return Agent entity if authentication is successful.
     * @throws SipAuthException if the calculated response does not match the client's response.
     */
    @Transactional(readOnly = true)
    public Agent authenticateSipAgentDigest(SipAuthRequest request) {
        // 1. Find the agent by username (which should be the extension)
        Agent agent = agentRepository.findByTenantIdAndUsername(request.getTenantId(), request.getUsername())
                .orElseThrow(() -> new SipAuthException("Agent not found for SIP auth: " + request.getUsername()));

        // The stored password is the one required for A1 calculation
        String sipPassword = agent.getPassword();

        // --- SIP Digest Authentication Steps ---

        // A1: Hash(username:realm:password)
        String a1Source = request.getUsername() + ":" + request.getRealm() + ":" + sipPassword;
        String ha1 = md5Hex(a1Source);

        // A2: Hash(method:uri)
        String a2Source = request.getMethod().toUpperCase() + ":" + request.getUri();
        String ha2 = md5Hex(a2Source);

        // Response: Hash(HA1:nonce:HA2)
        // NOTE: For REGISTER requests, `qop`, `cnonce`, and `nc` are typically not used by Kamailio,
        // so we use the simpler form: HA1:nonce:HA2.
        // If qop was present, the formula would be: HA1:nonce:nc:cnonce:qop:HA2
        String finalResponseSource = ha1 + ":" + request.getNonce() + ":" + ha2;
        String calculatedResponse = md5Hex(finalResponseSource);

        // 3. Compare calculated response with client's response
        if (!calculatedResponse.equalsIgnoreCase(request.getResponse())) {
            throw new SipAuthException("SIP Digest Response mismatch for user: " + request.getUsername());
        }

        // 4. Update agent status (optional, but good practice for registration)
        agent.updateLastSeen();
        agentRepository.save(agent); // Persist the lastSeen update

        return agent;
    }

    // --- Existing methods (configureAgent, etc.) should be here ---
    @Transactional
    public Agent configureAgent(String externalId, AgentConfigurationRequest configRequest) {
        Agent agent = agentRepository.findByExternalId(externalId)
                .orElseThrow(() -> new AgentNotFoundException("Agent not found with external ID: " + externalId));

        // Update fields from DTO
        if (configRequest.getExtension() != null) {
            agent.setExtension(configRequest.getExtension());
        }
        if (configRequest.getDisplayName() != null) {
            agent.setDisplayName(configRequest.getDisplayName());
        }
        if (configRequest.getEmail() != null) {
            agent.setEmail(configRequest.getEmail());
        }
        if (configRequest.getPhoneNumber() != null) {
            agent.setPhoneNumber(configRequest.getPhoneNumber());
        }
        if (configRequest.getMaxConcurrentCalls() != null) {
            agent.setMaxConcurrentCalls(configRequest.getMaxConcurrentCalls());
        }
        if (configRequest.getSkills() != null) {
            agent.setSkills(configRequest.getSkills());
        }
        if (configRequest.getQueueMemberships() != null) {
            agent.setQueueMemberships(configRequest.getQueueMemberships());
        }
        if (configRequest.getPreferences() != null) {
            agent.setPreferences(configRequest.getPreferences());
        }

        // Configuration steps:
        // 1. Set Agent Team ID (Mapped to pbx-core Lead ID)
        // This will be done here when the configuration is completed or later during a dedicated admin call.
        // For now, save the agent configuration.
        Agent updatedAgent = agentRepository.save(agent);

        // **Future Step for PBX-CORE:**
        // if (configRequest.getTeamId() != null) {
        //     pbxCoreClient.configureAgentTeam(agent.getTenantId(), agent.getExtension(), configRequest.getTeamId());
        // }

        return updatedAgent;
    }
}
