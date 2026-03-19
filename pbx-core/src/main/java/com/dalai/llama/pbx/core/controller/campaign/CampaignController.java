package com.dalai.llama.pbx.core.controller.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Campaign;
import com.dalai.llama.pbx.core.service.campaign.CampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    @PostMapping
    public ResponseEntity<Campaign> create(@RequestBody Campaign campaign) {
        return ResponseEntity.ok(campaignService.createCampaign(campaign));
    }

    @GetMapping
    public ResponseEntity<List<Campaign>> list(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(campaignService.getByTenantId(tenant_id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Campaign> get(@PathVariable UUID id) {
        return campaignService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Campaign> start(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.start(id));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Campaign> pause(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.pause(id));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Campaign> resume(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.resume(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Campaign> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(campaignService.cancel(id));
    }
}