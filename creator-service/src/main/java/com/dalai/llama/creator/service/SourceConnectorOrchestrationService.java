package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.connector.ConnectorFetchRequest;
import com.dalai.llama.creator.connector.ConnectorFetchResult;
import com.dalai.llama.creator.connector.SourceConnectorClient;
import com.dalai.llama.creator.connector.SourceConnectorClientRegistry;
import com.dalai.llama.creator.connector.StructuredTrendSignal;
import com.dalai.llama.creator.domain.ConnectorFailurePolicy;
import com.dalai.llama.creator.domain.ConnectorRunStatus;
import com.dalai.llama.creator.domain.entity.CreatorCategory;
import com.dalai.llama.creator.domain.entity.CreatorCategoryKeyword;
import com.dalai.llama.creator.domain.entity.CreatorConnectorCategoryMap;
import com.dalai.llama.creator.domain.entity.CreatorConnectorRun;
import com.dalai.llama.creator.domain.entity.CreatorConnectorRunDiff;
import com.dalai.llama.creator.domain.entity.CreatorSourceConnector;
import com.dalai.llama.creator.domain.entity.CreatorTrendCombination;
import com.dalai.llama.creator.domain.entity.CreatorTrendDump;
import com.dalai.llama.creator.domain.entity.CreatorTrendSignal;
import com.dalai.llama.creator.repository.CreatorCategoryKeywordRepository;
import com.dalai.llama.creator.repository.CreatorConnectorCategoryMapRepository;
import com.dalai.llama.creator.repository.CreatorConnectorRunDiffRepository;
import com.dalai.llama.creator.repository.CreatorConnectorRunRepository;
import com.dalai.llama.creator.repository.CreatorSourceConnectorRepository;
import com.dalai.llama.creator.repository.CreatorTrendCombinationRepository;
import com.dalai.llama.creator.repository.CreatorTrendDumpRepository;
import com.dalai.llama.creator.repository.CreatorTrendSignalRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SourceConnectorOrchestrationService {

    private final CreatorProperties properties;
    private final SourceConnectorClientRegistry clientRegistry;
    private final CreatorTrendCombinationRepository trendCombinationRepository;
    private final CreatorConnectorCategoryMapRepository connectorCategoryMapRepository;
    private final CreatorCategoryKeywordRepository categoryKeywordRepository;
    private final CreatorConnectorRunRepository connectorRunRepository;
    private final CreatorTrendDumpRepository trendDumpRepository;
    private final CreatorTrendSignalRepository trendSignalRepository;
    private final CreatorSourceConnectorRepository sourceConnectorRepository;
    private final CreatorConnectorRunDiffRepository connectorRunDiffRepository;

    public SourceConnectorOrchestrationService(
            CreatorProperties properties,
            SourceConnectorClientRegistry clientRegistry,
            CreatorTrendCombinationRepository trendCombinationRepository,
            CreatorConnectorCategoryMapRepository connectorCategoryMapRepository,
            CreatorCategoryKeywordRepository categoryKeywordRepository,
            CreatorConnectorRunRepository connectorRunRepository,
            CreatorTrendDumpRepository trendDumpRepository,
            CreatorTrendSignalRepository trendSignalRepository,
            CreatorSourceConnectorRepository sourceConnectorRepository,
            CreatorConnectorRunDiffRepository connectorRunDiffRepository
    ) {
        this.properties = properties;
        this.clientRegistry = clientRegistry;
        this.trendCombinationRepository = trendCombinationRepository;
        this.connectorCategoryMapRepository = connectorCategoryMapRepository;
        this.categoryKeywordRepository = categoryKeywordRepository;
        this.connectorRunRepository = connectorRunRepository;
        this.trendDumpRepository = trendDumpRepository;
        this.trendSignalRepository = trendSignalRepository;
        this.sourceConnectorRepository = sourceConnectorRepository;
        this.connectorRunDiffRepository = connectorRunDiffRepository;
    }

    public void collectOnce(String trigger) {
        OffsetDateTime windowEndedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime windowStartedAt = windowEndedAt.minusMinutes(properties.getTrends().getScheduler().getWindowMinutes());
        String countryCode = normalizeCountry(properties.getTrends().getScheduler().getCountryCode());

        List<CreatorTrendCombination> combinations =
                trendCombinationRepository.findByEnabledTrueOrderByPlatformCodeAscCategoryCodeAsc();

        for (CreatorTrendCombination combination : combinations) {
            List<CreatorConnectorCategoryMap> mappings =
                    connectorCategoryMapRepository.findRunnableMappingsForCategory(combination.getCategoryCode());

            for (CreatorConnectorCategoryMap mapping : mappings) {
                runConnectorSafely(mapping, combination, countryCode, windowStartedAt, windowEndedAt, trigger);
            }
        }
    }

    private void runConnectorSafely(
            CreatorConnectorCategoryMap mapping,
            CreatorTrendCombination combination,
            String countryCode,
            OffsetDateTime windowStartedAt,
            OffsetDateTime windowEndedAt,
            String trigger
    ) {
        CreatorSourceConnector connector = mapping.getConnector();
        CreatorCategory category = mapping.getCategory();

        CreatorConnectorRun run = connectorRunRepository.save(CreatorConnectorRun.builder()
                .connectorId(connector.getId())
                .connectorCode(connector.getCode())
                .targetPlatformCode(combination.getPlatformCode())
                .sourcePlatformCode(connector.getSourcePlatformCode())
                .categoryCode(category.getCode())
                .countryCode(countryCode)
                .windowStartedAt(windowStartedAt)
                .windowEndedAt(windowEndedAt)
                .callMethod(connector.getCallMethod())
                .status(ConnectorRunStatus.RUNNING)
                .attemptCount(0)
                .requestCount(0)
                .itemsFound(0)
                .requestSnapshot(Map.of("trigger", trigger))
                .responseSnapshot(Map.of())
                .structuredPayload(Map.of())
                .startedAt(OffsetDateTime.now())
                .build());

        if (isRateLimited(connector)) {
            markRateLimited(run);
            return;
        }

        List<CreatorCategoryKeyword> keywords =
                categoryKeywordRepository.findActiveByCategoryCodeAndSourceType(category.getCode(), connector.getSourcePlatformCode());
        if (keywords.isEmpty()) {
            keywords = categoryKeywordRepository.findActiveByCategoryCode(category.getCode());
        }

        int maxAttempts = Math.max(1, safeInt(connector.getRetryCount()) + 1);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                SourceConnectorClient client = clientRegistry.resolve(connector.getCallMethod());
                ConnectorFetchResult result = client.fetch(new ConnectorFetchRequest(
                        connector,
                        category,
                        keywords,
                        combination.getPlatformCode(),
                        countryCode,
                        windowStartedAt,
                        windowEndedAt
                ));

                persistSuccessfulRun(run, connector, category, combination, countryCode, result, attempt);
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt < maxAttempts) {
                    sleepBeforeRetry(connector);
                }
            }
        }

        persistFailedRun(run, connector, lastFailure, maxAttempts);
    }

    private boolean isRateLimited(CreatorSourceConnector connector) {
        OffsetDateTime now = OffsetDateTime.now();
        Integer perMinute = connector.getRateLimitPerMinute();
        if (perMinute != null && perMinute > 0
                && connectorRunRepository.countByConnectorCodeAndStartedAtAfter(connector.getCode(), now.minusMinutes(1)) > perMinute) {
            return true;
        }

        Integer perDay = connector.getRateLimitPerDay();
        if (perDay != null && perDay > 0
                && connectorRunRepository.countByConnectorCodeAndStartedAtAfter(connector.getCode(), now.minusDays(1)) > perDay) {
            return true;
        }

        Integer monthly = connector.getMonthlyCallLimit();
        return monthly != null && monthly > 0
                && connectorRunRepository.countByConnectorCodeAndStartedAtAfter(connector.getCode(), now.minusDays(30)) > monthly;
    }

    private void markRateLimited(CreatorConnectorRun run) {
        run.setStatus(ConnectorRunStatus.RATE_LIMITED);
        run.setCompletedAt(OffsetDateTime.now());
        run.setErrorCode("RATE_LIMITED");
        run.setErrorMessage("Connector call was skipped because configured rate limit was reached.");
        connectorRunRepository.save(run);
    }

    private void persistSuccessfulRun(
            CreatorConnectorRun run,
            CreatorSourceConnector connector,
            CreatorCategory category,
            CreatorTrendCombination combination,
            String countryCode,
            ConnectorFetchResult result,
            int attempt
    ) {
        run.setStatus(ConnectorRunStatus.COMPLETED);
        run.setAttemptCount(attempt);
        run.setRequestCount(result.requestCount());
        run.setItemsFound(result.signals().size());
        run.setHttpStatus(result.httpStatus());
        run.setCompletedAt(OffsetDateTime.now());
        run.setRequestSnapshot(safeMap(result.requestSnapshot()));
        run.setResponseSnapshot(safeMap(result.responseSnapshot()));
        run.setStructuredPayload(safeMap(result.structuredPayload()));
        connectorRunRepository.save(run);

        trendDumpRepository.save(CreatorTrendDump.builder()
                .platformCode(combination.getPlatformCode())
                .categoryCode(category.getCode())
                .countryCode(countryCode)
                .windowStartedAt(run.getWindowStartedAt())
                .windowEndedAt(run.getWindowEndedAt())
                .sourceName(connector.getCode())
                .sourceUrl(connector.getBaseUrl())
                .rawPayload(safeMap(result.structuredPayload()))
                .status("COLLECTED")
                .build());

        List<String> newKeys = new java.util.ArrayList<>();
        List<String> duplicateKeys = new java.util.ArrayList<>();
        for (StructuredTrendSignal signal : result.signals()) {
            boolean inserted = saveSignal(run, connector, combination, category, countryCode, signal);
            if (inserted) {
                newKeys.add(signal.dedupeKey());
            } else {
                duplicateKeys.add(signal.dedupeKey());
            }
        }

        connectorRunDiffRepository.save(CreatorConnectorRunDiff.builder()
                .connectorRunId(run.getId())
                .connectorCode(connector.getCode())
                .targetPlatformCode(combination.getPlatformCode())
                .categoryCode(category.getCode())
                .countryCode(countryCode)
                .incomingCount(result.signals().size())
                .newCount(newKeys.size())
                .duplicateCount(duplicateKeys.size())
                .changedCount(0)
                .missingCount(0)
                .diffPayload(ConnectorClientDiffPayloads.payload(newKeys, duplicateKeys))
                .build());
    }

    private boolean saveSignal(
            CreatorConnectorRun run,
            CreatorSourceConnector connector,
            CreatorTrendCombination combination,
            CreatorCategory category,
            String countryCode,
            StructuredTrendSignal signal
    ) {
        try {
            trendSignalRepository.save(CreatorTrendSignal.builder()
                    .connectorRunId(run.getId())
                    .connectorCode(connector.getCode())
                    .sourcePlatformCode(connector.getSourcePlatformCode())
                    .targetPlatformCode(combination.getPlatformCode())
                    .categoryCode(category.getCode())
                    .countryCode(countryCode)
                    .sourceType(connector.getCallMethod().name())
                    .sourceUrl(signal.sourceUrl())
                    .title(signal.title())
                    .summary(signal.summary())
                    .signalText(signal.signalText())
                    .sourceAuthor(signal.sourceAuthor())
                    .engagementScore(signal.engagementScore())
                    .rankScore(signal.rankScore())
                    .publishedAt(signal.publishedAt())
                    .observedAt(signal.observedAt())
                    .rawItem(safeMap(signal.rawItem()))
                    .normalizedPayload(safeMap(signal.normalizedPayload()))
                    .dedupeKey(signal.dedupeKey())
                    .build());
            return true;
        } catch (DataIntegrityViolationException ignored) {
            // Duplicate signals are expected across overlapping scheduler windows.
            return false;
        }
    }

    private void persistFailedRun(
            CreatorConnectorRun run,
            CreatorSourceConnector connector,
            RuntimeException failure,
            int attemptCount
    ) {
        run.setStatus(ConnectorRunStatus.FAILED);
        run.setAttemptCount(attemptCount);
        run.setCompletedAt(OffsetDateTime.now());
        run.setErrorCode(failure == null ? "UNKNOWN" : failure.getClass().getSimpleName());
        run.setErrorMessage(failure == null ? "Unknown connector failure" : failure.getMessage());
        connectorRunRepository.save(run);

        if (ConnectorFailurePolicy.DISABLE_CONNECTOR == connector.getFailurePolicy()) {
            connector.setEnabled(false);
            sourceConnectorRepository.save(connector);
        }
    }

    private void sleepBeforeRetry(CreatorSourceConnector connector) {
        long backoff = Math.max(0, safeInt(connector.getRetryBackoffMs()));
        if (backoff == 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Connector retry interrupted", ex);
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeCountry(String value) {
        if (value == null || value.isBlank()) {
            return "IN";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
}
