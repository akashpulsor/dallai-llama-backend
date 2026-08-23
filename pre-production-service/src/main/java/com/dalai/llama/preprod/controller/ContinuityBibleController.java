package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ContinuityBibleView;
import com.dalai.llama.preprod.service.ContinuityBibleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ContinuityBibleController extends BaseController {

    private final ContinuityBibleService continuityBibleService;

    public ContinuityBibleController(ContinuityBibleService continuityBibleService) {
        this.continuityBibleService = continuityBibleService;
    }

    @GetMapping("/v1/projects/{projectId}/continuity-bible")
    public ResponseEntity<ContinuityBibleView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(continuityBibleService.getView(tenant().tenantId(), projectId));
    }
}
