package com.dalai.llama.tenant.leadmanagement.outreach;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** Sends queued audience mail (rule 26), oldest first, one transaction per mail so one bad address
 * never holds up the rest. Runs every minute; each run takes at most {@code BATCH}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutreachDispatchJob {

    static final int BATCH = 100;

    private final OutreachStore store;
    private final IntentDispatcher dispatcher;
    private final TransactionTemplate transactions;

    public record RunSummary(int sent, int waitingForDigest, int undeliverable, int suppressed) {
    }

    @Scheduled(fixedDelayString = "${outreach.dispatch-delay-ms:60000}", initialDelayString = "${outreach.dispatch-delay-ms:60000}")
    public void scheduled() {
        RunSummary summary = run();
        int total = summary.sent() + summary.waitingForDigest() + summary.undeliverable() + summary.suppressed();
        if (total > 0) log.info("Outreach dispatch: {}", summary);
    }

    public RunSummary run() {
        Map<IntentDispatcher.Result, Integer> counts = new EnumMap<>(IntentDispatcher.Result.class);
        for (UUID id : store.queuedIds(BATCH)) {
            try {
                IntentDispatcher.Result result = transactions.execute(tx -> store.lockQueued(id).map(dispatcher::dispatch).orElse(null));
                if (result != null) counts.merge(result, 1, Integer::sum);
            } catch (RuntimeException e) {
                log.warn("Dispatch failed for intent {}: {}", id, e.getMessage());
            }
        }
        return new RunSummary(counts.getOrDefault(IntentDispatcher.Result.SENT, 0),
                counts.getOrDefault(IntentDispatcher.Result.WAITING_FOR_DIGEST, 0),
                counts.getOrDefault(IntentDispatcher.Result.UNDELIVERABLE, 0),
                counts.getOrDefault(IntentDispatcher.Result.SUPPRESSED, 0));
    }
}
