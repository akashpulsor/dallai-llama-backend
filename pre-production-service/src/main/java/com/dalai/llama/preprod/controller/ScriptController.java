package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.GenerateScriptRequest;
import com.dalai.llama.preprod.dto.SaveScriptEditRequest;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptVersionView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.UpdateScriptCharacterRequest;
import com.dalai.llama.preprod.service.ScriptGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @PatchMapping("/v1/projects/{projectId}/script/characters/{characterId}")
    public ResponseEntity<ScriptCharacterView> updateCharacter(
            @PathVariable UUID projectId, @PathVariable UUID characterId, @RequestBody UpdateScriptCharacterRequest request) {
        return ResponseEntity.ok(scriptGenerationService.updateCharacter(tenant().tenantId(), projectId, characterId, request));
    }

    /** Every version ever saved for this project, oldest first -- backs version navigation. */
    @GetMapping("/v1/projects/{projectId}/script/versions")
    public ResponseEntity<List<ScriptVersionView>> listVersions(@PathVariable UUID projectId) {
        return ResponseEntity.ok(scriptGenerationService.listVersions(tenant().tenantId(), projectId));
    }

    /** One specific version -- what "previous"/"next version" reads. */
    @GetMapping("/v1/projects/{projectId}/script/versions/{version}")
    public ResponseEntity<ScriptVersionView> getVersion(@PathVariable UUID projectId, @PathVariable Integer version) {
        return ResponseEntity.ok(scriptGenerationService.getVersion(tenant().tenantId(), projectId, version));
    }

    /** Saves a manual script edit as a new EDITED version of {@code fromVersion} -- no LLM call. */
    @PostMapping("/v1/projects/{projectId}/script/versions/{fromVersion}/edit")
    public ResponseEntity<ScriptView> saveEdit(
            @PathVariable UUID projectId, @PathVariable Integer fromVersion, @Valid @RequestBody SaveScriptEditRequest request) {
        return ResponseEntity.ok(scriptGenerationService.saveEdit(tenant().tenantId(), projectId, fromVersion, request));
    }
}
