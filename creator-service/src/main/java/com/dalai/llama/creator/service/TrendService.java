package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorTrend;
import com.dalai.llama.creator.dto.response.CreatorTrendResponse;
import com.dalai.llama.creator.repository.CreatorTrendRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;

@Service
public class TrendService {

    private static final String INDIA_COUNTRY_CODE = "IN";

    private final CreatorTrendRepository trendRepository;

    public TrendService(CreatorTrendRepository trendRepository) {
        this.trendRepository = trendRepository;
    }

    @Transactional(readOnly = true)
    public Page<CreatorTrendResponse> listTrends(
            String platform,
            String category,
            String country,
            Integer days,
            String timeframe,
            Pageable pageable
    ) {
        Integer effectiveDays = resolveDays(days, timeframe);
        return trendRepository.findRankedTrends(
                normalizeLower(platform),
                normalizeLower(category),
                trendCountryCode(),
                effectiveDays == null ? null : OffsetDateTime.now().minusDays(effectiveDays),
                pageable
        ).map(this::toResponse);
    }

    private CreatorTrendResponse toResponse(CreatorTrend trend) {
        return new CreatorTrendResponse(
                trend.getId(),
                trend.getPlatformCode(),
                trend.getCategoryCode(),
                trend.getCountryCode(),
                trend.getTitle(),
                trend.getSummary(),
                trend.getSourceName(),
                trend.getSourceUrl(),
                trend.getScore(),
                trend.getVelocity(),
                trend.getFirstSeenAt(),
                trend.getLastSeenAt()
        );
    }

    private String normalizeLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String trendCountryCode() {
        // Creator trend reads are intentionally India-only for the current rollout.
        return INDIA_COUNTRY_CODE;
    }

    private Integer resolveDays(Integer days, String timeframe) {
        if (days != null && days > 0) {
            return Math.min(days, 365);
        }
        if (timeframe == null || timeframe.isBlank()) {
            return 7;
        }
        String normalized = timeframe.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "24h", "1d", "last_24_hours" -> 1;
            case "30d", "last_30_days" -> 30;
            case "90d", "last_90_days" -> 90;
            default -> 7;
        };
    }
}
