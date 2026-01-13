package com.dalai.llama.agent.service;


import com.dalai.llama.agent.dto.AllocateAgentRequest;
import com.dalai.llama.agent.dto.AllocateAgentResponse;
import com.dalai.llama.agent.dto.ResolveAgentRequest;
import com.dalai.llama.agent.dto.ResolveAgentResponse;
import com.dalai.llama.agent.dto.UnassignAgentRequest;
import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRoutingService {

    private final AgentRepository agentRepository;
    private final PresenceStore presenceStore;
    private final RedisTemplate<String, String> redis;

    // assignment TTL — prevents agents stuck as assigned forever (configurable)
    private final long ASSIGN_TTL_SECONDS = 60 * 5; // 5 minutes default

    private String assignedKey(String tenantId, Long agentId) {
        return "call:assigned:" + tenantId + ":" + agentId;
    }

    // Allocate: find an effectively online, available, not-assigned agent and mark assigned
    public AllocateAgentResponse allocateAgent(AllocateAgentRequest req) {
        List<Agent> agents = agentRepository.findByTenantId(req.getTenantId());
        if (agents == null || agents.isEmpty()) {
            return AllocateAgentResponse.builder().agentId(null).contact(null).reason("no-agents").build();
        }

        for (Agent a : agents) {
            Map<String, String> pres = presenceStore.getPresence(req.getTenantId(), a.getId());
            if (pres == null || pres.isEmpty()) continue;

            boolean effectiveOnline = "true".equalsIgnoreCase(pres.get("effectiveOnline"));
            String effectiveAvail = pres.get("effectiveAvailability");
            if (!effectiveOnline) continue;
            if (!"AVAILABLE".equalsIgnoreCase(effectiveAvail)) continue;

            // check assignment
            String assigned = redis.opsForValue().get(assignedKey(req.getTenantId(), a.getId()));
            if ("true".equalsIgnoreCase(assigned)) continue;

            String contact = pres.get("contact");
            if (contact == null || contact.isBlank()) continue;

            // mark assigned (set with TTL to avoid endless locks)
            redis.opsForValue().set(assignedKey(req.getTenantId(), a.getId()), "true", ASSIGN_TTL_SECONDS, TimeUnit.SECONDS);

            log.info("Allocated agent {} for tenant {}, contact={}", a.getId(), req.getTenantId(), contact);
            return AllocateAgentResponse.builder()
                    .agentId(String.valueOf(a.getId()))
                    .contact(contact)
                    .build();
        }

        return AllocateAgentResponse.builder().agentId(null).contact(null).reason("no-available-agents").build();
    }

    // Resolve: given agentId return contact (from presence). No allocation changes.
    public ResolveAgentResponse resolveContact(ResolveAgentRequest req) {
        Long agentId = safeParseId(req.getAgentId());
        if (agentId == null) {
            return ResolveAgentResponse.builder().agentId(req.getAgentId()).contact(null).build();
        }
        Map<String, String> pres = presenceStore.getPresence(req.getTenantId(), agentId);
        if (pres == null || pres.isEmpty()) {
            return ResolveAgentResponse.builder().agentId(req.getAgentId()).contact(null).build();
        }
        return ResolveAgentResponse.builder().agentId(req.getAgentId()).contact(pres.get("contact")).build();
    }

    // Unassign: called when call ends (PBX/Core should call). Clears assigned flag.
    public void unassign(UnassignAgentRequest req) {
        Long agentId = safeParseId(req.getAgentId());
        if (agentId == null) return;
        String key = assignedKey(req.getTenantId(), agentId);
        redis.delete(key);
        log.info("Unassigned agent {} for tenant {}", agentId, req.getTenantId());
    }

    private Long safeParseId(String s) {
        try { return Long.valueOf(s); } catch (Exception e) { return null; }
    }
}

