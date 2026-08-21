package com.dalai.llama.trendintel.controller;

import com.dalai.llama.trendintel.dto.GenerateTrendReportRequest;
import com.dalai.llama.trendintel.dto.TrendReportView;
import com.dalai.llama.trendintel.service.TrendReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** chat-service's GENERATE_TREND_REPORT action is the real caller -- {@code /api/v1/internal/**}
 * is permitAll (see {@code SecurityConfig}), tenantId comes from the path. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalTrendReportController {

    private final TrendReportService trendReportService;

    public InternalTrendReportController(TrendReportService trendReportService) {
        this.trendReportService = trendReportService;
    }

    @PostMapping("/trend-reports")
    public TrendReportView generate(@PathVariable UUID tenantId, @Valid @RequestBody GenerateTrendReportRequest request) {
        return trendReportService.generate(tenantId, request);
    }
}
