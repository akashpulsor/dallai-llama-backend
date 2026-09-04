package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.CameraPlanView;
import com.dalai.llama.preprod.dto.SaveCameraPlanEditRequest;
import com.dalai.llama.preprod.service.CameraPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class CameraPlanController extends BaseController {

    private final CameraPlanService cameraPlanService;

    public CameraPlanController(CameraPlanService cameraPlanService) {
        this.cameraPlanService = cameraPlanService;
    }

    @PostMapping("/v1/shots/{shotId}/camera-plan/generate")
    public ResponseEntity<CameraPlanView> generate(@PathVariable UUID shotId) {
        return ResponseEntity.ok(cameraPlanService.generate(tenant().tenantId(), shotId));
    }

    @GetMapping("/v1/shots/{shotId}/camera-plan")
    public ResponseEntity<CameraPlanView> get(@PathVariable UUID shotId) {
        return ResponseEntity.ok(cameraPlanService.get(tenant().tenantId(), shotId));
    }

    @PatchMapping("/v1/shots/{shotId}/camera-plan")
    public ResponseEntity<CameraPlanView> saveEdit(@PathVariable UUID shotId, @RequestBody SaveCameraPlanEditRequest request) {
        return ResponseEntity.ok(cameraPlanService.saveEdit(tenant().tenantId(), shotId, request));
    }
}
