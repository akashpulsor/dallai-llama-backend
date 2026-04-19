package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.RecurringCharge;
import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.repository.RecurringChargeRepository;
import com.dalai.llama.billing.repository.TransactionRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics & billing summary endpoints.
 *
 * Provides:
 *   GET  /api/v1/billing/{tenantId}/analytics          → Dashboard-level KPIs
 *   GET  /api/v1/billing/{tenantId}/monthly             → Monthly billing summaries
 *   GET  /api/v1/billing/{tenantId}/invoice/{month}     → Download invoice (simple text for now)
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Tenant analytics and billing summary APIs")
public class AnalyticsController {

    private final WalletService walletService;
    private final WalletRepository walletRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final TransactionRepository transactionRepository;
    private final RecurringChargeRepository recurringChargeRepository;

    // ═══════════════════════════════════════════════════════════
    // 1. TENANT ANALYTICS — useGetTenantAnalyticsQuery
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{tenantId}/analytics")
    @Operation(summary = "Get tenant analytics", description = "KPI snapshot: spend, usage, trends")
    public ResponseEntity<Map<String, Object>> getTenantAnalytics(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {

        Instant now = Instant.now();
        Instant periodStart = now.minus(java.time.Duration.ofDays(days));
        YearMonth currentMonth = YearMonth.now();
        Instant monthStart = currentMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant monthEnd = currentMonth.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();

        Map<String, Object> analytics = new LinkedHashMap<>();

        // ── Wallet ──
        try {
            BigDecimal balance = walletService.getBalance(tenantId);
            analytics.put("wallet_balance", balance);
            analytics.put("currency", "INR");
        } catch (Exception e) {
            analytics.put("wallet_balance", BigDecimal.ZERO);
            analytics.put("currency", "INR");
        }

        // ── Current month spend ──
        BigDecimal monthSpend = usageRecordRepository.sumCostByTenantIdAndPeriod(tenantId, monthStart, monthEnd);
        analytics.put("current_month_spend", monthSpend);

        // ── Period spend (last N days) ──
        BigDecimal periodSpend = usageRecordRepository.sumCostByTenantIdAndPeriod(tenantId, periodStart, now);
        analytics.put("period_spend", periodSpend);
        analytics.put("period_days", days);

        // ── Call counts by metric ──
        for (UsageMetric metric : UsageMetric.values()) {
            BigDecimal qty = usageRecordRepository.sumQuantityByTenantIdAndMetricAndPeriod(
                    tenantId, metric, monthStart, monthEnd);
            analytics.put("month_" + metric.name().toLowerCase(), qty);
        }

        // ── Usage records this month ──
        long usageCount = usageRecordRepository.countByTenantIdAndRecordedAtBetween(tenantId, monthStart, monthEnd);
        analytics.put("month_usage_records", usageCount);

        // ── Recharges this month ──
        BigDecimal recharges = transactionRepository.sumAmountByTenantIdAndTypeAndPeriod(
                tenantId, TransactionType.RECHARGE, monthStart, monthEnd);
        analytics.put("month_recharges", recharges);

        // ── Recurring charges ──
        List<RecurringCharge> activeCharges = recurringChargeRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");
        BigDecimal monthlyRecurring = activeCharges.stream()
                .map(RecurringCharge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        analytics.put("monthly_recurring_total", monthlyRecurring);

        // ── Spend trend (last 6 months) ──
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth m = currentMonth.minusMonths(i);
            Instant s = m.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant e = m.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
            BigDecimal cost = usageRecordRepository.sumCostByTenantIdAndPeriod(tenantId, s, e);
            trend.add(Map.of("month", m.toString(), "spend", cost));
        }
        analytics.put("spend_trend", trend);

        return ResponseEntity.ok(analytics);
    }

    // ═══════════════════════════════════════════════════════════
    // 2. MONTHLY BILLING SUMMARY — useGetMonthlyBillingQuery
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{tenantId}/monthly")
    @Operation(summary = "Get monthly billing summaries", description = "Usage + charges per month")
    public ResponseEntity<List<Map<String, Object>>> getMonthlyBilling(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "6") int months) {

        YearMonth current = YearMonth.now();
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (int i = 0; i < months; i++) {
            YearMonth month = current.minusMonths(i);
            Instant start = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant end = month.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();

            // Usage breakdown by metric
            List<UsageRecord> records = usageRecordRepository.findByTenantIdAndRecordedAtBetween(tenantId, start, end);
            Map<String, BigDecimal> usageByMetric = records.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.getMetric().name(),
                            Collectors.reducing(BigDecimal.ZERO, UsageRecord::getTotalCost, BigDecimal::add)));

            BigDecimal usageTotal = records.stream()
                    .map(UsageRecord::getTotalCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Transactions
            List<Transaction> transactions = transactionRepository
                    .findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(tenantId, start, end);

            BigDecimal totalDebits = transactions.stream()
                    .filter(t -> t.getAmount().signum() < 0)
                    .map(t -> t.getAmount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCredits = transactions.stream()
                    .filter(t -> t.getAmount().signum() > 0)
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("month", month.toString());
            summary.put("period_start", start.toString());
            summary.put("period_end", end.toString());
            summary.put("usage_total", usageTotal);
            summary.put("usage_breakdown", usageByMetric);
            summary.put("usage_record_count", records.size());
            summary.put("total_debits", totalDebits);
            summary.put("total_credits", totalCredits);
            summary.put("net_spend", totalDebits.subtract(totalCredits));
            summary.put("transaction_count", transactions.size());

            summaries.add(summary);
        }

        return ResponseEntity.ok(summaries);
    }

    // ═══════════════════════════════════════════════════════════
    // 3. INVOICE DOWNLOAD — useDownloadInvoiceMutation
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/{tenantId}/invoice/{month}")
    @Operation(summary = "Download invoice", description = "Generate invoice for a given month (YYYY-MM)")
    public ResponseEntity<byte[]> downloadInvoice(
            @PathVariable UUID tenantId,
            @PathVariable String month) {

        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        Instant start = ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = ym.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();

        // Gather invoice data
        List<UsageRecord> records = usageRecordRepository.findByTenantIdAndRecordedAtBetween(tenantId, start, end);
        List<Transaction> transactions = transactionRepository
                .findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(tenantId, start, end);

        Map<String, BigDecimal> usageByMetric = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getMetric().name(),
                        Collectors.reducing(BigDecimal.ZERO, UsageRecord::getTotalCost, BigDecimal::add)));

        BigDecimal totalUsage = records.stream()
                .map(UsageRecord::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Generate a simple text-based invoice (can be upgraded to PDF with iText/OpenPDF later)
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("               DALAI LLAMA INVOICE\n");
        sb.append("═══════════════════════════════════════════════════\n\n");
        sb.append("Tenant ID:    ").append(tenantId).append("\n");
        sb.append("Period:       ").append(ym.toString()).append("\n");
        sb.append("Generated:    ").append(Instant.now()).append("\n\n");

        sb.append("─── USAGE BREAKDOWN ───────────────────────────────\n");
        for (Map.Entry<String, BigDecimal> entry : usageByMetric.entrySet()) {
            sb.append(String.format("  %-35s ₹%10s\n", entry.getKey(), entry.getValue().setScale(2, RoundingMode.HALF_UP)));
        }
        sb.append("  ─────────────────────────────────────────────────\n");
        sb.append(String.format("  %-35s ₹%10s\n", "TOTAL USAGE", totalUsage.setScale(2, RoundingMode.HALF_UP)));
        sb.append("\n");

        sb.append("─── TRANSACTIONS ──────────────────────────────────\n");
        for (Transaction tx : transactions) {
            sb.append(String.format("  %s  %-15s  ₹%10s  %s\n",
                    tx.getCreatedAt().toString().substring(0, 10),
                    tx.getType().name(),
                    tx.getAmount().setScale(2, RoundingMode.HALF_UP),
                    tx.getReference() != null ? tx.getReference() : ""));
        }

        sb.append("\n═══════════════════════════════════════════════════\n");

        byte[] content = sb.toString().getBytes();
        String filename = "invoice-" + tenantId.toString().substring(0, 8) + "-" + month + ".txt";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(content.length)
                .body(content);
    }
}
