package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.TaskLabel;
import com.dalai.llama.billing.domain.UsageStage;
import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.repository.TransactionRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A wallet statement a creator can actually read.
 *
 * <p>The billing page listed every transaction in one undifferentiated run, so the numbers moved
 * constantly and said nothing: 929 rows all reading "AI usage: gemini-2.5-flash", no way to see
 * which part of a project spent the money, and a balance that changed without explanation.
 *
 * <p>This answers the question a creator is actually asking -- "I put money in; where has it
 * gone since?" -- by anchoring on the last credit and totalling the spend after it, grouped by
 * the stage of production that spent it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletStatementService {

    /** Money going IN. Everything else on a wallet is money going out. */
    private static final List<TransactionType> CREDIT_TYPES =
            List.of(TransactionType.RECHARGE, TransactionType.ADJUSTMENT_CREDIT, TransactionType.REFUND);

    private final TransactionRepository transactionRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final CurrencyConversionService currencyConversionService;

    @Transactional(readOnly = true)
    public StatementView statement(UUID tenantId) {
        List<Transaction> transactions = transactionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        Transaction lastCredit = transactions.stream()
                .filter(t -> t.getType() != null && CREDIT_TYPES.contains(t.getType()))
                .findFirst()
                .orElse(null);

        // "Since" means since the money arrived. With no credit ever recorded, the whole history
        // is the period -- which is the honest reading, not an empty statement.
        Instant since = lastCredit == null ? null : lastCredit.getCreatedAt();

        List<UsageRecord> usage = usageRecordRepository.findByTenantIdOrderByRecordedAtDesc(tenantId).stream()
                .filter(r -> since == null || (r.getRecordedAt() != null && !r.getRecordedAt().isBefore(since)))
                .toList();

        Map<UsageStage, StageTotal> byStage = new EnumMap<>(UsageStage.class);
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal totalTokens = BigDecimal.ZERO;
        for (UsageRecord record : usage) {
            UsageStage stage = record.getStage() == null ? UsageStage.OTHER : record.getStage();
            BigDecimal cost = record.getTotalCost() == null ? BigDecimal.ZERO : record.getTotalCost();
            // Quantity is the billed unit -- tokens for an LLM call, seconds for a render. Summed
            // per stage because cost alone cannot tell a creator whether a stage is expensive
            // because it ran often or because each run was large.
            BigDecimal units = record.getQuantity() == null ? BigDecimal.ZERO : record.getQuantity();
            StageTotal current = byStage.get(stage);
            byStage.put(stage, current == null
                    ? new StageTotal(stage, stage.label(), stage.description(), cost, units, 1)
                    : new StageTotal(stage, stage.label(), stage.description(),
                            current.amount().add(cost), current.units().add(units), current.calls() + 1));
            spent = spent.add(cost);
            totalTokens = totalTokens.add(units);
        }

        List<StageTotal> stages = new ArrayList<>(byStage.values());
        // Biggest spend first: the point of the breakdown is to show where the money went, and
        // that is the line someone is looking for.
        stages.sort(Comparator.comparing(StageTotal::amount).reversed());

        return new StatementView(
                lastCredit == null ? null : lastCredit.getAmount(),
                since,
                lastCredit == null ? null : lastCredit.getDescription(),
                spent,
                usage.size(),
                totalTokens,
                currencyConversionService.normalize(null),
                stages);
    }

    /** One row per billable call, newest first, for a creator who wants the detail behind a
     * stage total -- and the same rows the CSV export writes. */
    @Transactional(readOnly = true)
    public List<StatementLine> lines(UUID tenantId, Instant from, Instant to) {
        return usageRecordRepository.findByTenantIdOrderByRecordedAtDesc(tenantId).stream()
                .filter(r -> from == null || (r.getRecordedAt() != null && !r.getRecordedAt().isBefore(from)))
                .filter(r -> to == null || (r.getRecordedAt() != null && !r.getRecordedAt().isAfter(to)))
                .map(r -> new StatementLine(
                        r.getRecordedAt(),
                        r.getStage() == null ? UsageStage.OTHER.label() : r.getStage().label(),
                        r.getTaskKey(),
                        // What happened, in words. The task key stays on the line for anyone
                        // reconciling against logs, but it is not what a bill should read.
                        TaskLabel.describe(r.getTaskKey()) != null
                                ? TaskLabel.describe(r.getTaskKey())
                                : r.getDescription(),
                        r.getProjectId(),
                        r.getQuantity(),
                        r.getUnit() == null ? null : r.getUnit().name(),
                        r.getTotalCost()))
                .toList();
    }

    /** Statement lines as CSV -- a bank statement someone can open in a spreadsheet and reconcile
     * against, which is the only form in which this data is genuinely checkable. */
    public String csv(List<StatementLine> lines, String currency) {
        StringBuilder out = new StringBuilder();
        out.append("Date,Stage,What happened,Task key,Project,Quantity,Unit,Amount (").append(currency).append(")\n");
        for (StatementLine line : lines) {
            out.append(csvCell(line.recordedAt() == null ? "" : line.recordedAt().toString())).append(',')
                    .append(csvCell(line.stage())).append(',')
                    .append(csvCell(line.description())).append(',')
                    .append(csvCell(line.taskKey())).append(',')
                    .append(csvCell(line.projectId() == null ? "" : line.projectId().toString())).append(',')
                    .append(csvCell(line.quantity() == null ? "" : line.quantity().toPlainString())).append(',')
                    .append(csvCell(line.unit())).append(',')
                    .append(csvCell(line.amount() == null ? "" : line.amount().toPlainString()))
                    .append('\n');
        }
        return out.toString();
    }

    /** A description can contain a comma or a quote, and a spreadsheet that mis-parses one row
     * shifts every column after it. */
    private String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    public record StatementView(
            BigDecimal lastCreditAmount,
            Instant lastCreditAt,
            String lastCreditDescription,
            BigDecimal spentSince,
            int callsSince,
            /** Billed units across the period -- tokens for LLM work, seconds for renders. */
            BigDecimal unitsSince,
            String currency,
            List<StageTotal> stages
    ) {}

    public record StageTotal(UsageStage stage, String label, String description,
                             BigDecimal amount, BigDecimal units, int calls) {}

    public record StatementLine(
            Instant recordedAt,
            String stage,
            String taskKey,
            String description,
            UUID projectId,
            BigDecimal quantity,
            String unit,
            BigDecimal amount
    ) {}
}
