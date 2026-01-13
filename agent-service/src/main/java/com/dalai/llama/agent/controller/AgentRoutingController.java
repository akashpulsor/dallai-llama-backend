package com.dalai.llama.agent.controller;

import com.dalai.llama.agent.dto.*;
import com.dalai.llama.agent.service.AgentRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentRoutingController {

    private final AgentRoutingService routing;

    @PostMapping("/allocate")
    public ResponseEntity<AllocateAgentResponse> allocate(@RequestBody AllocateAgentRequest req) {
        return ResponseEntity.ok(routing.allocateAgent(req));
    }

    @PostMapping("/resolve")
    public ResponseEntity<ResolveAgentResponse> resolve(@RequestBody ResolveAgentRequest req) {
        return ResponseEntity.ok(routing.resolveContact(req));
    }

    @PostMapping("/unassign")
    public ResponseEntity<Void> unassign(@RequestBody UnassignAgentRequest req) {
        routing.unassign(req);
        return ResponseEntity.ok().build();
    }
}
