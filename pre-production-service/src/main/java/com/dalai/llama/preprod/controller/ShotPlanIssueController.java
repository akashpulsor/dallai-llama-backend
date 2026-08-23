package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ShotPlanIssueView;
import com.dalai.llama.preprod.service.ShotPlanQualityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotPlanIssueController extends BaseController {

    private final ShotPlanQualityService shotPlanQualityService;

    public ShotPlanIssueController(ShotPlanQualityService shotPlanQualityService) {
        this.shotPlanQualityService = shotPlanQualityService;
    }

    @GetMapping("/v1/projects/{projectId}/shot-plan-issues")
    public ResponseEntity<List<ShotPlanIssueView>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotPlanQualityService.list(tenant().tenantId(), projectId));
    }
}
