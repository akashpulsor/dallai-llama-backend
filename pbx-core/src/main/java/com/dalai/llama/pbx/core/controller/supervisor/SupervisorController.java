package com.dalai.llama.pbx.core.controller.supervisor;


import com.dalai.llama.pbx.core.service.supervisor.SupervisorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SupervisorController {

    private final SupervisorService supervisorService;

    @PostMapping("/calls/{callId}/listen")
    public ResponseEntity<Map<String, String>> listen(
            @PathVariable String callId,
            @RequestBody Map<String, String> body) {
        String result = supervisorService.listen(body.get("supervisor_uuid"), callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/calls/{callId}/whisper")
    public ResponseEntity<Map<String, String>> whisper(
            @PathVariable String callId,
            @RequestBody Map<String, String> body) {
        String result = supervisorService.whisper(body.get("supervisor_uuid"), callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @PostMapping("/calls/{callId}/barge")
    public ResponseEntity<Map<String, String>> barge(
            @PathVariable String callId,
            @RequestBody Map<String, String> body) {
        String result = supervisorService.barge(body.get("supervisor_uuid"), callId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    @GetMapping("/supervisor/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(supervisorService.getDashboard(tenant_id));
    }

    @GetMapping("/supervisor/agent/{agentId}/call")
    public ResponseEntity<Map<String, String>> findAgentCall(
            @PathVariable UUID agentId,
            @RequestParam UUID tenant_id) {
        Optional<String> callId = supervisorService.findAgentActiveCall(tenant_id, agentId);
        return callId.map(id -> ResponseEntity.ok(Map.of("call_id", id)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/supervisor/ai-insights")
    public ResponseEntity<Map<String, Object>> aiInsights(
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(supervisorService.getAiInsights(tenant_id, days));
    }

    @GetMapping("/supervisor/leaderboard")
    public ResponseEntity<List<Map<String, Object>>> agentLeaderboard(
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(supervisorService.getAgentLeaderboard(tenant_id, days));
    }

    @GetMapping("/supervisor/team-metrics")
    public ResponseEntity<Map<String, Object>> teamMetrics(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(supervisorService.getTeamMetrics(tenant_id));
    }

    @GetMapping("/supervisor/flagged-calls")
    public ResponseEntity<List<Map<String, Object>>> flaggedCalls(
            @RequestParam UUID tenant_id,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "-0.3") double threshold) {
        return ResponseEntity.ok(supervisorService.getFlaggedCalls(tenant_id, days, threshold));
    }
}