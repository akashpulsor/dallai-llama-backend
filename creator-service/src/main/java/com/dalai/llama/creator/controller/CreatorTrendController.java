package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.CreatorTrendResponse;
import com.dalai.llama.creator.dto.request.TrendPredictionRequest;
import com.dalai.llama.creator.dto.response.TrendPredictionResponse;
import com.dalai.llama.creator.dto.response.TrendInsightResponse;
import com.dalai.llama.creator.service.TrendInsightService;
import com.dalai.llama.creator.service.TrendService;
import com.dalai.llama.creator.service.TrendPredictionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/trends")
public class CreatorTrendController {

    private final TrendService trendService;
    private final TrendPredictionService trendPredictionService;
    private final TrendInsightService trendInsightService;

    public CreatorTrendController(
            TrendService trendService,
            TrendPredictionService trendPredictionService,
            TrendInsightService trendInsightService
    ) {
        this.trendService = trendService;
        this.trendPredictionService = trendPredictionService;
        this.trendInsightService = trendInsightService;
    }

    @GetMapping
    public ResponseEntity<Page<CreatorTrendResponse>> listTrends(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "IN") String country,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String timeframe,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ResponseEntity.ok(trendService.listTrends(platform, category, country, days, timeframe, pageable));
    }

    @PostMapping("/predict")
    public ResponseEntity<TrendPredictionResponse> predictTrends(
            @Valid @RequestBody TrendPredictionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(trendPredictionService.predict(request, tenantId, userId));
    }

    @GetMapping("/{trendId}/insight")
    public ResponseEntity<TrendInsightResponse> getTrendInsight(
            @PathVariable UUID trendId,
            @RequestParam(required = false, defaultValue = "IN") String country,
            @RequestParam(required = false) String timezone,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(trendInsightService.getInsight(trendId, tenantId, userId, country, timezone));
    }
}
