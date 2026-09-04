package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.IdeaOptionView;
import com.dalai.llama.creativeplanning.dto.LockedIdeaView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectIdeaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Idea versioning for an already-created project -- generate alternatives to the current idea,
 * list every one ever generated, switch to a different one. Deliberately under
 * {@code /v1/project-ideas}, not {@code /v1/projects/...} -- pre-production-service already owns
 * the {@code /v1/projects} prefix at the Istio gateway, so reusing it here would collide. */
@RestController
public class ProjectIdeaController extends BaseController {

    private final ProjectIdeaService projectIdeaService;

    public ProjectIdeaController(ProjectIdeaService projectIdeaService) {
        this.projectIdeaService = projectIdeaService;
    }

    @PostMapping("/v1/project-ideas/{projectId}/generate")
    public ResponseEntity<List<IdeaOptionView>> generateOptions(
            @PathVariable UUID projectId, @RequestParam(required = false) Integer count) {
        return ResponseEntity.ok(projectIdeaService.generateOptions(tenant().tenantId(), projectId, count));
    }

    @GetMapping("/v1/project-ideas/{projectId}")
    public ResponseEntity<List<IdeaOptionView>> listOptions(@PathVariable UUID projectId) {
        return ResponseEntity.ok(projectIdeaService.listOptions(tenant().tenantId(), projectId));
    }

    @PostMapping("/v1/project-ideas/{projectId}/{ideaOptionId}/select")
    public ResponseEntity<LockedIdeaView> select(@PathVariable UUID projectId, @PathVariable UUID ideaOptionId) {
        return ResponseEntity.ok(projectIdeaService.switchToOption(tenant().tenantId(), projectId, ideaOptionId));
    }
}
