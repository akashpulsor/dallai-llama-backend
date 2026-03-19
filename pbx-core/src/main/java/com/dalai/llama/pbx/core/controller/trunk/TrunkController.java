package com.dalai.llama.pbx.core.controller.trunk;


import com.dalai.llama.pbx.core.domain.entity.core.SipTrunk;
import com.dalai.llama.pbx.core.domain.enums.TrunkAuthType;
import com.dalai.llama.pbx.core.domain.enums.TrunkTransport;
import com.dalai.llama.pbx.core.service.trunk.TrunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trunks")
@RequiredArgsConstructor
public class TrunkController {

    private final TrunkService trunkService;

    @PostMapping
    public ResponseEntity<SipTrunk> create(@RequestBody Map<String, Object> body) {
        SipTrunk trunk = trunkService.createTrunk(
                UUID.fromString(body.get("tenant_id").toString()),
                UUID.fromString(body.get("subscription_id").toString()),
                (String) body.get("name"),
                (String) body.get("provider"),
                (String) body.get("sip_server"),
                body.get("sip_port") instanceof Number n ? n.intValue() : null,
                body.get("transport") != null ? TrunkTransport.valueOf(body.get("transport").toString()) : null,
                body.get("auth_type") != null ? TrunkAuthType.valueOf(body.get("auth_type").toString()) : null,
                (String) body.get("auth_username"),
                (String) body.get("auth_password"),
                body.get("max_concurrent") instanceof Number n ? n.intValue() : null,
                (String) body.get("outbound_caller_id"),
                (String) body.get("codec_preference")
        );
        return ResponseEntity.ok(trunk);
    }

    @GetMapping
    public ResponseEntity<List<SipTrunk>> list(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(trunkService.getByTenantId(tenant_id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SipTrunk> get(@PathVariable UUID id) {
        return trunkService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        trunkService.deleteTrunk(id);
        return ResponseEntity.noContent().build();
    }
}