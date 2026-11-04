package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryService.BrandHistory;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryService.Shortfall;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryService.VideoCoverage;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.BrandMailSummary;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.TemplateStats;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Ops page "Mail brands" tab: what brands were sent and did, how templates do, and video coverage.
 * Under the ops-routed {@code /api/v1/internal/admin/tenants/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/creator-mail/history")
public class AdminOpsMailHistoryController {

    private final OpsMailHistoryService service;

    @GetMapping("/brands")
    public ResponseEntity<List<BrandMailSummary>> brands(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(service.brands(q));
    }

    @GetMapping("/brand")
    public ResponseEntity<BrandHistory> brand(@RequestParam String email) {
        return ResponseEntity.ok(service.brand(email));
    }

    @GetMapping("/templates")
    public ResponseEntity<List<TemplateStats>> templates() {
        return ResponseEntity.ok(service.templateStats());
    }

    @GetMapping("/coverage")
    public ResponseEntity<List<VideoCoverage>> coverage() {
        return ResponseEntity.ok(service.coverage());
    }

    @GetMapping("/shortfalls")
    public ResponseEntity<List<Shortfall>> shortfalls() {
        return ResponseEntity.ok(service.shortfalls());
    }
}
