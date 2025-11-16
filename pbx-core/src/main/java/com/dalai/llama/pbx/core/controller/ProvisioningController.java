package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.dto.DidRequest;
import com.dalai.llama.pbx.core.dto.TenantProvisioningRequest;
import com.dalai.llama.pbx.core.dto.TrunkRequest;
import com.dalai.llama.pbx.core.model.Did;
import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.model.Trunk;
import com.dalai.llama.pbx.core.repository.DidRepository;
import com.dalai.llama.pbx.core.repository.TrunkRepository;
import com.dalai.llama.pbx.core.provision.ProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tenants/{tenantId}")
@RequiredArgsConstructor
public class ProvisioningController {

    private final ProvisioningService provisioningService;
    private final TrunkRepository trunkRepository;
    private final DidRepository didRepository;

    @PostMapping("/provision/signaling")
    public ResponseEntity<SignalingConfig> provision(@PathVariable String tenantId,
                                                     @Valid @RequestBody TenantProvisioningRequest req) {
        return ResponseEntity.ok(provisioningService.upsert(tenantId, req));
    }

    @PostMapping("/trunks")
    public ResponseEntity<Trunk> addTrunk(@PathVariable String tenantId,
                                          @Valid @RequestBody TrunkRequest req) {
        Trunk t = Trunk.builder()
                .tenantId(tenantId)
                .name(req.getName())
                .sipUri(req.getSipUri())
                .username(req.getUsername())
                .password(req.getPassword())
                .region(req.getRegion())
                .enabled(req.isEnabled())
                .build();
        return ResponseEntity.ok(trunkRepository.save(t));
    }

    @PostMapping("/dids")
    public ResponseEntity<Did> addDid(@PathVariable String tenantId,
                                      @Valid @RequestBody DidRequest req) {
        Did d = Did.builder()
                .tenantId(tenantId)
                .number(req.getNumber())
                .entrypoint(req.getEntrypoint())
                .trunkId(req.getTrunkId())
                .status(req.getStatus()==null?"active":req.getStatus())
                .build();
        return ResponseEntity.ok(didRepository.save(d));
    }
}
