package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.ProjectSpendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Tenant-authenticated read of a project's real accumulated spend against its quoted price --
 * creator-ui's budget-used indicator. Same {@link ProjectSpendService} the dispatch-time cap
 * check (llm-gateway -> InternalBillingController) uses, one source of truth for both; this is
 * just the JWT-reachable sibling of that internal-only endpoint. */
@RestController
@RequestMapping("/api/v1/billing/{tenantId}/projects/{projectId}")
@RequiredArgsConstructor
@Tag(name = "Project Spend", description = "Real accumulated spend for a project against its quoted price")
public class ProjectSpendController {

    private final ProjectSpendService projectSpendService;

    @GetMapping("/spend")
    @Operation(summary = "Get project spend")
    public ResponseEntity<ProjectSpendView> getSpend(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        ProjectSpendService.SpendStatus spend = projectSpendService.checkCap(tenantId, projectId);
        return ResponseEntity.ok(new ProjectSpendView(spend.totalSpent(), spend.quotedTotalPrice(), spend.withinCap(), "INR"));
    }

    public record ProjectSpendView(BigDecimal totalSpent, BigDecimal quotedTotalPrice, boolean withinCap, String currency) {}
}
