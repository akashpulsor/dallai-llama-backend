package com.dalai.llama.agent.controller;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_agent:read')")
    public ResponseEntity<List<Agent>> listAgents(@RequestParam String tenantId) {
        return ResponseEntity.ok(agentService.findByTenant(tenantId));
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

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('SCOPE_agent:write')")
    public ResponseEntity<Void> setOnlineStatus(
            @PathVariable Long id,
            @RequestParam boolean online) {
        agentService.setOnlineStatus(id, online);
        return ResponseEntity.ok().build();
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
}
