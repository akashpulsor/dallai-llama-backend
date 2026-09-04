package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.dto.response.UsageDetailResponse;
import com.dalai.llama.billing.dto.response.UsageSummaryResponse;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/billing/{tenantId}/usage")
@RequiredArgsConstructor
@Tag(name = "Usage", description = "Usage tracking and reporting APIs")
public class UsageController {

    private final UsageRecordRepository usageRecordRepository;

    @GetMapping
    @Operation(summary = "Get usage summary", description = "Get aggregated usage summary for current month")
    public ResponseEntity<UsageSummaryResponse> getUsageSummary(@PathVariable UUID tenantId) {
        YearMonth currentMonth = YearMonth.now();
        Instant startOfMonth = currentMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endOfMonth = currentMonth.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();

        List<UsageRecord> records = usageRecordRepository.findByTenantId(tenantId).stream()
                .filter(r -> r.getRecordedAt() != null &&
                        !r.getRecordedAt().isBefore(startOfMonth) &&
                        !r.getRecordedAt().isAfter(endOfMonth))
                .toList();

        Map<String, BigDecimal> usageByMetric = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getMetric().name(),
                        Collectors.reducing(BigDecimal.ZERO, UsageRecord::getTotalCost, BigDecimal::add)
                ));

        BigDecimal totalCost = records.stream()
                .map(UsageRecord::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(UsageSummaryResponse.builder()
                .tenantId(tenantId)
                .periodStart(startOfMonth)
                .periodEnd(endOfMonth)
                .usageByMetric(usageByMetric)
                .totalCost(totalCost)
                .build());
    }

    @GetMapping("/details")
    @Operation(summary = "Get usage details", description = "Get detailed usage breakdown by metric")
    public ResponseEntity<List<UsageDetailResponse>> getUsageDetails(@PathVariable UUID tenantId) {
        YearMonth currentMonth = YearMonth.now();
        Instant startOfMonth = currentMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endOfMonth = currentMonth.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();

        List<UsageRecord> records = usageRecordRepository.findByTenantId(tenantId).stream()
                .filter(r -> r.getRecordedAt() != null &&
                        !r.getRecordedAt().isBefore(startOfMonth) &&
                        !r.getRecordedAt().isAfter(endOfMonth))
                .toList();

        Map<String, List<UsageRecord>> groupedByMetric = records.stream()
                .collect(Collectors.groupingBy(r -> r.getMetric().name()));

        List<UsageDetailResponse> details = groupedByMetric.entrySet().stream()
                .map(entry -> {
                    List<UsageRecord> metricRecords = entry.getValue();
                    BigDecimal totalQuantity = metricRecords.stream()
                            .map(UsageRecord::getQuantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal totalCost = metricRecords.stream()
                            .map(UsageRecord::getTotalCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    String unit = metricRecords.isEmpty() ? "" : metricRecords.get(0).getUnit().name();

                    return UsageDetailResponse.builder()
                            .metric(entry.getKey())
                            .quantity(totalQuantity)
                            .unit(unit)
                            .totalCost(totalCost)
                            .recordCount(metricRecords.size())
                            .build();
                })
                .toList();

        return ResponseEntity.ok(details);
    }

    /** Individual line items, newest first -- the actual "debit log" the wallet & billing tab
     * shows, as opposed to {@link #getUsageSummary}/{@link #getUsageDetails}'s aggregates. Every
     * real-time LLM-usage debit (see LlmBillingEventConsumer) lands here the same way any other
     * usage-sourced debit does, since both go through the same {@code UsageService.recordBillableUsage}. */
    @GetMapping("/records")
    @Operation(summary = "Get recent usage records", description = "Individual usage/debit line items, newest first")
    public ResponseEntity<List<UsageRecordView>> getUsageRecords(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<UsageRecordView> records = usageRecordRepository.findByTenantId(tenantId).stream()
                .sorted((a, b) -> b.getRecordedAt().compareTo(a.getRecordedAt()))
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(r -> new UsageRecordView(
                        r.getId(), r.getMetric().name(), r.getQuantity(), r.getUnit().name(),
                        r.getUnitCost(), r.getTotalCost(), r.getSourceType(), r.getSourceId(),
                        r.getDescription(), r.getRecordedAt()))
                .toList();
        return ResponseEntity.ok(records);
    }

    public record UsageRecordView(
            UUID id, String metric, BigDecimal quantity, String unit,
            BigDecimal unitCost, BigDecimal totalCost, String sourceType, UUID sourceId,
            String description, Instant recordedAt
    ) {
    }

    @GetMapping("/history")
    @Operation(summary = "Get usage history", description = "Get historical usage by month")
    public ResponseEntity<List<MonthlyUsageResponse>> getUsageHistory(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "6") int months
    ) {
        List<UsageRecord> allRecords = usageRecordRepository.findByTenantId(tenantId);

        YearMonth currentMonth = YearMonth.now();

        List<MonthlyUsageResponse> history = java.util.stream.IntStream.range(0, months)
                .mapToObj(i -> currentMonth.minusMonths(i))
                .map(month -> {
                    Instant start = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    Instant end = month.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();

                    List<UsageRecord> monthRecords = allRecords.stream()
                            .filter(r -> r.getRecordedAt() != null &&
                                    !r.getRecordedAt().isBefore(start) &&
                                    !r.getRecordedAt().isAfter(end))
                            .toList();

                    BigDecimal totalCost = monthRecords.stream()
                            .map(UsageRecord::getTotalCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    Map<String, BigDecimal> breakdown = monthRecords.stream()
                            .collect(Collectors.groupingBy(
                                    r -> r.getMetric().name(),
                                    Collectors.reducing(BigDecimal.ZERO, UsageRecord::getTotalCost, BigDecimal::add)
                            ));

                    return MonthlyUsageResponse.builder()
                            .month(month.toString())
                            .totalCost(totalCost)
                            .breakdown(breakdown)
                            .build();
                })
                .toList();

        return ResponseEntity.ok(history);
    }

    @lombok.Builder
    @lombok.Getter
    public static class MonthlyUsageResponse {
        private String month;
        private BigDecimal totalCost;
        private Map<String, BigDecimal> breakdown;
    }
}
