package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.CreateCastAssignmentRequest;
import com.dalai.llama.preprod.dto.CreateCastProfileRequest;
import com.dalai.llama.preprod.service.CastAssignmentService;
import com.dalai.llama.preprod.service.CastProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CastController extends BaseController {

    private final CastProfileService castProfileService;
    private final CastAssignmentService castAssignmentService;

    public CastController(CastProfileService castProfileService, CastAssignmentService castAssignmentService) {
        this.castProfileService = castProfileService;
        this.castAssignmentService = castAssignmentService;
    }

    @PostMapping("/v1/cast-profiles")
    public ResponseEntity<CastProfileView> createProfile(@Valid @RequestBody CreateCastProfileRequest request) {
        return ResponseEntity.ok(castProfileService.create(tenant().tenantId(), request));
    }

    @GetMapping("/v1/cast-profiles")
    public ResponseEntity<List<CastProfileView>> listProfiles(@RequestParam(required = false) UUID projectId) {
        return ResponseEntity.ok(castProfileService.list(tenant().tenantId(), projectId));
    }

    @PostMapping("/v1/projects/{projectId}/cast-assignments")
    public ResponseEntity<CastAssignmentView> assign(@PathVariable UUID projectId, @Valid @RequestBody CreateCastAssignmentRequest request) {
        return ResponseEntity.ok(castAssignmentService.assign(tenant().tenantId(), projectId, request));
    }

    @GetMapping("/v1/projects/{projectId}/cast-assignments")
    public ResponseEntity<List<CastAssignmentView>> listAssignments(@PathVariable UUID projectId) {
        return ResponseEntity.ok(castAssignmentService.list(projectId));
    }
}
