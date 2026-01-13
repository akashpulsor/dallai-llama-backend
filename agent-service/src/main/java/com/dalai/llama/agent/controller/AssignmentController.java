package com.dalai.llama.agent.controller;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.service.AssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/assignments")
@RequiredArgsConstructor
public class
AssignmentController {
    private final AssignmentService service;

    @PostMapping("/request")
    public ResponseEntity<?> requestAssignment(@RequestParam String tenantId, @RequestParam String callId) {
        Optional<Agent> opt = null;//service.assignAgent(tenantId);
        if (opt.isPresent()) {
            Agent a = opt.get();
            //service.publishAssignment(tenantId, callId, a);
            return ResponseEntity.ok(java.util.Map.of("assigned", true, "agentId", a.getId()));
        } else {
            return ResponseEntity.ok(java.util.Map.of("assigned", false));
        }
    }
}