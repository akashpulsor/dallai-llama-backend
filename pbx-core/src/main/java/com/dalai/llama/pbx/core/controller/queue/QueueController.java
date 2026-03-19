package com.dalai.llama.pbx.core.controller.queue;


import com.dalai.llama.pbx.core.domain.entity.core.Queue;
import com.dalai.llama.pbx.core.domain.entity.core.QueueMember;
import com.dalai.llama.pbx.core.domain.enums.QueueStrategy;
import com.dalai.llama.pbx.core.service.queue.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/queues")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @PostMapping
    public ResponseEntity<Queue> create(@RequestBody Map<String, Object> body) {
        Queue queue = queueService.createQueue(
                UUID.fromString(body.get("tenant_id").toString()),
                UUID.fromString(body.get("subscription_id").toString()),
                (String) body.get("name"),
                body.get("strategy") != null ? QueueStrategy.valueOf(body.get("strategy").toString()) : null,
                body.get("max_wait_seconds") instanceof Number n ? n.intValue() : null,
                (String) body.get("moh_file"),
                body.get("wrap_up_seconds") instanceof Number n ? n.intValue() : null
        );
        return ResponseEntity.ok(queue);
    }

    @GetMapping
    public ResponseEntity<List<Queue>> list(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(queueService.getByTenantId(tenant_id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Queue> get(@PathVariable UUID id) {
        return queueService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(queueService.getQueueStats(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<QueueMember> addMember(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        QueueMember member = queueService.addMember(
                id,
                UUID.fromString(body.get("agent_id").toString()),
                body.get("priority") instanceof Number n ? n.intValue() : null,
                body.get("penalty") instanceof Number n ? n.intValue() : null
        );
        return ResponseEntity.ok(member);
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<QueueMember>> members(@PathVariable UUID id) {
        return ResponseEntity.ok(queueService.getMembers(id));
    }

    @DeleteMapping("/{queueId}/members/{agentId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID queueId, @PathVariable UUID agentId) {
        queueService.removeMember(queueId, agentId);
        return ResponseEntity.noContent().build();
    }
}