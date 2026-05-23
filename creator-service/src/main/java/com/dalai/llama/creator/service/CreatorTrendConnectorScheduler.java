package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CreatorTrendConnectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(CreatorTrendConnectorScheduler.class);

    private final CreatorProperties properties;
    private final SourceConnectorOrchestrationService orchestrationService;

    public CreatorTrendConnectorScheduler(
            CreatorProperties properties,
            SourceConnectorOrchestrationService orchestrationService
    ) {
        this.properties = properties;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(
            fixedDelayString = "${creator.trends.scheduler.fixed-delay-ms:1800000}",
            initialDelayString = "${creator.trends.scheduler.initial-delay-ms:60000}"
    )
    public void collectTrendSignals() {
        if (!properties.getTrends().getScheduler().isEnabled()) {
            log.info("Creator trend scheduler skipped because creator.trends.scheduler.enabled=false");
            return;
        }
        log.info("Creator trend scheduler fired");
        orchestrationService.collectOnce("SCHEDULED");
    }
}
