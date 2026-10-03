package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.ProjectEconomics;
import com.dalai.llama.billing.service.ProjectEconomicsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The creator's per-project statement on the Wallet & Billing page: the production charges the
 * client was shown, what the client paid, and the creator's profit. Platform figures (provider
 * cost, margin) are stripped here -- they are only served on the ops admin route. Same
 * tenant-in-path, JWT-reachable shape as {@link ProjectSpendController}. */
@RestController
@RequestMapping("/api/v1/billing/{tenantId}/projects")
@RequiredArgsConstructor
@Tag(name = "Project Economics", description = "Per-project production charges, client payments and creator profit")
public class ProjectEconomicsController {

    private final ProjectEconomicsService projectEconomicsService;

    @GetMapping("/economics")
    @Operation(summary = "Every project's economics for this tenant")
    public ResponseEntity<List<ProjectEconomics>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(projectEconomicsService.forTenant(tenantId).stream()
                .map(ProjectEconomics::forCreator)
                .toList());
    }

    @GetMapping("/{projectId}/economics")
    @Operation(summary = "One project's economics")
    public ResponseEntity<ProjectEconomics> get(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(projectEconomicsService.forProject(tenantId, projectId).forCreator());
    }
}
