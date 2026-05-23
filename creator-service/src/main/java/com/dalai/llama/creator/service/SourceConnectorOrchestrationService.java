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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SourceConnectorOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(SourceConnectorOrchestrationService.class);
    private static final String INDIA_COUNTRY_CODE = "IN";

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
        String countryCode = schedulerCountryCode();

        List<CreatorTrendCombination> combinations =
                trendCombinationRepository.findByEnabledTrueOrderByPlatformCodeAscCategoryCodeAsc();

        log.info(
                "Creator trend ingestion started trigger={} country={} windowStart={} windowEnd={} activeCombinations={}",
                trigger,
                countryCode,
                windowStartedAt,
                windowEndedAt,
                combinations.size()
        );

        if (combinations.isEmpty()) {
            log.warn("Creator trend ingestion skipped trigger={} country={} reason=no_active_platform_category_combinations", trigger, countryCode);
        }

        for (CreatorTrendCombination combination : combinations) {
            List<CreatorConnectorCategoryMap> mappings =
                    connectorCategoryMapRepository.findRunnableMappingsForCategory(combination.getCategoryCode());

            log.info(
                    "Creator trend ingestion combination targetPlatform={} category={} country={} connectorMappings={}",
                    combination.getPlatformCode(),
                    combination.getCategoryCode(),
                    countryCode,
                    mappings.size()
            );

            for (CreatorConnectorCategoryMap mapping : mappings) {
                runConnectorSafely(mapping, combination, countryCode, windowStartedAt, windowEndedAt, trigger);
            }
        }

        log.info(
                "Creator trend ingestion finished trigger={} country={} windowStart={} windowEnd={} activeCombinations={}",
                trigger,
                countryCode,
                windowStartedAt,
                windowEndedAt,
                combinations.size()
        );
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

        log.info(
                "Creator trend connector run started trigger={} runId={} connector={} sourcePlatform={} targetPlatform={} category={} country={}",
                trigger,
                run.getId(),
                connector.getCode(),
                connector.getSourcePlatformCode(),
                combination.getPlatformCode(),
                category.getCode(),
                countryCode
        );

        if (isRateLimited(connector)) {
            markRateLimited(run);
            return;
        }

        List<CreatorCategoryKeyword> keywords =
                categoryKeywordRepository.findActiveByCategoryCodeAndSourceType(category.getCode(), connector.getSourcePlatformCode());
        if (keywords.isEmpty()) {
            keywords = categoryKeywordRepository.findActiveByCategoryCode(category.getCode());
        }

        if (keywords.isEmpty()) {
            log.warn(
                    "Creator trend connector run has no keywords runId={} connector={} targetPlatform={} category={} country={}",
                    run.getId(),
                    connector.getCode(),
                    combination.getPlatformCode(),
                    category.getCode(),
                    countryCode
            );
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
        log.warn(
                "Creator trend connector run rate limited runId={} connector={} targetPlatform={} category={} country={}",
                run.getId(),
                run.getConnectorCode(),
                run.getTargetPlatformCode(),
                run.getCategoryCode(),
                run.getCountryCode()
        );
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

        CreatorTrendDump trendDump = trendDumpRepository.save(CreatorTrendDump.builder()
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

        log.info(
                "Creator trend signals persisted runId={} dumpId={} connector={} sourcePlatform={} targetPlatform={} category={} country={} httpStatus={} requests={} incomingSignals={} newSignals={} duplicateSignals={}",
                run.getId(),
                trendDump.getId(),
                connector.getCode(),
                connector.getSourcePlatformCode(),
                combination.getPlatformCode(),
                category.getCode(),
                countryCode,
                result.httpStatus(),
                result.requestCount(),
                result.signals().size(),
                newKeys.size(),
                duplicateKeys.size()
        );
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

        log.error(
                "Creator trend connector run failed runId={} connector={} targetPlatform={} category={} country={} attempts={} errorCode={} errorMessage={}",
                run.getId(),
                run.getConnectorCode(),
                run.getTargetPlatformCode(),
                run.getCategoryCode(),
                run.getCountryCode(),
                attemptCount,
                run.getErrorCode(),
                run.getErrorMessage(),
                failure
        );
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

    private String schedulerCountryCode() {
        String configured = properties.getTrends().getScheduler().getCountryCode();
        if (configured == null || configured.isBlank()) {
            return INDIA_COUNTRY_CODE;
        }
        // Creator trend ingestion is intentionally India-only for now.
        return INDIA_COUNTRY_CODE;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }
}
