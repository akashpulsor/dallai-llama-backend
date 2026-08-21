package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.GenerateScriptRequest;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.service.ScriptGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ScriptController extends BaseController {

    private final ScriptGenerationService scriptGenerationService;

    public ScriptController(ScriptGenerationService scriptGenerationService) {
        this.scriptGenerationService = scriptGenerationService;
    }

    @PostMapping("/v1/projects/{projectId}/script/generate")
    public ResponseEntity<ScriptView> generate(@PathVariable UUID projectId, @Valid @RequestBody GenerateScriptRequest request) {
        return ResponseEntity.ok(scriptGenerationService.generate(tenant().tenantId(), projectId, request));
    }

    @GetMapping("/v1/projects/{projectId}/script")
    public ResponseEntity<ScriptView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(scriptGenerationService.get(tenant().tenantId(), projectId));
    }
}
