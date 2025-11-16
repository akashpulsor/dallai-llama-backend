package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.dto.RoutingPolicyRequest;
import com.dalai.llama.pbx.core.model.RoutingPolicy;
import com.dalai.llama.pbx.core.repository.RoutingPolicyRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/v1/routing/policies")
@RequiredArgsConstructor
public class RoutingPolicyController {

    private final RoutingPolicyRepository repo;

    @PutMapping("/{tenantId}/{entrypoint}")
    public ResponseEntity<RoutingPolicy> upsert(@PathVariable String tenantId,
                                                @PathVariable String entrypoint,
                                                @Valid @RequestBody RoutingPolicyRequest req) {
        RoutingPolicy p = repo.findByTenantIdAndEntrypoint(tenantId, entrypoint)
                .orElseGet(RoutingPolicy::new);
        p.setTenantId(tenantId);
        p.setEntrypoint(entrypoint);
        p.setStrategy(req.getStrategy());
        p.setTeamId(req.getTeamId());
        p.setSkills(req.getSkills());
        p.setFailover(req.getFailover());
        if (req.getAiHook()!=null) {
            p.setAiEndpoint(req.getAiHook().getEndpoint());
            p.setAiTimeoutMs(req.getAiHook().getTimeoutMs());
        }
        p.setUpdatedAt(Instant.now());
        p.setVersion(p.getVersion()==null?1:p.getVersion()+1);
        return ResponseEntity.ok(repo.save(p));
    }

    @GetMapping("/{tenantId}/{entrypoint}")
    public ResponseEntity<RoutingPolicy> get(@PathVariable String tenantId,
                                             @PathVariable String entrypoint) {
        return repo.findByTenantIdAndEntrypoint(tenantId, entrypoint)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
