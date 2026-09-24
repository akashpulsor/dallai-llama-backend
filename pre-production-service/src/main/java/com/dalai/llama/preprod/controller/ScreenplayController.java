package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.GenerateScreenplayRequest;
import com.dalai.llama.preprod.dto.SaveScreenplayEditRequest;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.service.ScreenplayGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ScreenplayController extends BaseController {

    private final ScreenplayGenerationService screenplayGenerationService;

    public ScreenplayController(ScreenplayGenerationService screenplayGenerationService) {
        this.screenplayGenerationService = screenplayGenerationService;
    }

    /** Always creates a new version -- see ScreenplayGenerationService's class javadoc. The body
     * is optional: existing callers that POST no body still work (Spring binds a null
     * {@link GenerateScreenplayRequest}). Present body lets the creator pick a dialogue language
     * at generation time -- see GenerateScreenplayRequest javadoc for why. */
    @PostMapping("/v1/projects/{projectId}/screenplay/generate")
    public ResponseEntity<ScreenplayView> generate(
            @PathVariable UUID projectId,
            @RequestBody(required = false) GenerateScreenplayRequest request) {
        return ResponseEntity.ok(screenplayGenerationService.generate(tenant().tenantId(), projectId, request));
    }

    /** Regenerates with the current live script text folded back in plus an optional note --
     * same rationale as {@code ScriptController#regenerate}, exposing the previously-hidden
     * {@code ScreenplayGenerationService#regenerateWithNote} used only by ChangeRequestService
     * before. Every call still inserts a new version (screenplay is versioned; nothing is
     * overwritten). */
    @PostMapping("/v1/projects/{projectId}/screenplay/regenerate")
    public ResponseEntity<ScreenplayView> regenerate(
            @PathVariable UUID projectId,
            @RequestBody(required = false) RegenerateScreenplayRequest request) {
        String note = request == null ? "" : (request.note() == null ? "" : request.note());
        return ResponseEntity.ok(screenplayGenerationService.regenerateWithNote(tenant().tenantId(), projectId, note));
    }

    /** Optional creator note folded into the LLM prompt on top of the current script text. */
    public record RegenerateScreenplayRequest(String note) {}

    /** Latest version. */
    @GetMapping("/v1/projects/{projectId}/screenplay")
    public ResponseEntity<ScreenplayView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.get(tenant().tenantId(), projectId));
    }

    /** Every version ever saved for this project, oldest first -- backs version navigation. */
    @GetMapping("/v1/projects/{projectId}/screenplay/versions")
    public ResponseEntity<List<ScreenplayView>> listVersions(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.listVersions(tenant().tenantId(), projectId));
    }

    /** One specific version -- what "previous"/"next version" reads. */
    @GetMapping("/v1/projects/{projectId}/screenplay/versions/{version}")
    public ResponseEntity<ScreenplayView> getVersion(@PathVariable UUID projectId, @PathVariable Integer version) {
        return ResponseEntity.ok(screenplayGenerationService.getVersion(tenant().tenantId(), projectId, version));
    }

    /** Saves a manual scene edit as a new EDITED version of {@code fromVersion} -- no LLM call. */
    @PostMapping("/v1/projects/{projectId}/screenplay/versions/{fromVersion}/edit")
    public ResponseEntity<ScreenplayView> saveEdit(
            @PathVariable UUID projectId, @PathVariable Integer fromVersion, @Valid @RequestBody SaveScreenplayEditRequest request) {
        return ResponseEntity.ok(screenplayGenerationService.saveEdit(tenant().tenantId(), projectId, fromVersion, request));
    }
}
