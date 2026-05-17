package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CreatorTrendConnectorScheduler {

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
            return;
        }
        orchestrationService.collectOnce("SCHEDULED");
    }
}
