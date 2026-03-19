package com.dalai.llama.pbx.core.controller.routing;


import com.dalai.llama.pbx.core.domain.entity.core.IvrFlow;
import com.dalai.llama.pbx.core.domain.entity.core.RoutingPolicy;
import com.dalai.llama.pbx.core.domain.enums.RoutingActionType;
import com.dalai.llama.pbx.core.domain.enums.RoutingMatchType;
import com.dalai.llama.pbx.core.service.routing.RoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/routing")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingService routingService;

    @PostMapping("/policies")
    @SuppressWarnings("unchecked")
    public ResponseEntity<RoutingPolicy> createPolicy(@RequestBody Map<String, Object> body) {
        RoutingPolicy policy = routingService.createPolicy(
                UUID.fromString(body.get("tenant_id").toString()),
                UUID.fromString(body.get("subscription_id").toString()),
                (String) body.get("name"),
                body.get("match_type") != null ? RoutingMatchType.valueOf(body.get("match_type").toString()) : null,
                (String) body.get("match_value"),
                RoutingActionType.valueOf(body.get("action_type").toString()),
                (String) body.get("action_target"),
                body.get("priority") instanceof Number n ? n.intValue() : null,
                (Map<String, Object>) body.get("time_condition")
        );
        return ResponseEntity.ok(policy);
    }

    @GetMapping("/policies")
    public ResponseEntity<List<RoutingPolicy>> listPolicies(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(routingService.getPolicies(tenant_id));
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable UUID id) {
        routingService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ivr-flows")
    @SuppressWarnings("unchecked")
    public ResponseEntity<IvrFlow> createIvrFlow(@RequestBody Map<String, Object> body) {
        IvrFlow flow = routingService.createIvrFlow(
                UUID.fromString(body.get("tenant_id").toString()),
                UUID.fromString(body.get("subscription_id").toString()),
                (String) body.get("name"),
                (Map<String, Object>) body.get("flow_json")
        );
        return ResponseEntity.ok(flow);
    }

    @GetMapping("/ivr-flows")
    public ResponseEntity<List<IvrFlow>> listIvrFlows(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(routingService.getIvrFlows(tenant_id));
    }
}