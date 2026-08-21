package com.dalai.llama.trendintel.controller;

import com.dalai.llama.trendintel.dto.GenerateTrendReportRequest;
import com.dalai.llama.trendintel.dto.TrendReportView;
import com.dalai.llama.trendintel.service.TrendReportService;
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
public class TrendReportController extends BaseController {

    private final TrendReportService trendReportService;

    public TrendReportController(TrendReportService trendReportService) {
        this.trendReportService = trendReportService;
    }

    @PostMapping("/v1/trend-reports")
    public ResponseEntity<TrendReportView> generate(@Valid @RequestBody GenerateTrendReportRequest request) {
        return ResponseEntity.ok(trendReportService.generate(tenant().tenantId(), request));
    }

    @GetMapping("/v1/trend-reports/{reportId}")
    public ResponseEntity<TrendReportView> get(@PathVariable UUID reportId) {
        return ResponseEntity.ok(trendReportService.get(tenant().tenantId(), reportId));
    }

    @GetMapping("/v1/trend-reports")
    public ResponseEntity<List<TrendReportView>> list() {
        return ResponseEntity.ok(trendReportService.list(tenant().tenantId()));
    }
}
