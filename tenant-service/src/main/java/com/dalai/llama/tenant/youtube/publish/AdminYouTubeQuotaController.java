package com.dalai.llama.tenant.youtube.publish;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops: today's YouTube quota use, the upload queue, and the budget (raise it after Google grants
 * more quota). Under the ops-routed {@code /api/v1/internal/admin/tenants/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/youtube/quota")
public class AdminYouTubeQuotaController {

    private final YouTubeQuotaService quota;

    public record BudgetRequest(@Min(100) @Max(10_000_000) int dailyLimit, @Min(1) @Max(100_000) int uploadUnits) {
    }

    @GetMapping
    public ResponseEntity<YouTubeQuotaService.QuotaView> view() {
        return ResponseEntity.ok(quota.view());
    }

    @PutMapping
    public ResponseEntity<YouTubeQuotaService.QuotaView> setBudget(@Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.ok(quota.setBudget(request.dailyLimit(), request.uploadUnits()));
    }
}
