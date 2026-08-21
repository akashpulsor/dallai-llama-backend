package com.dalai.llama.chat.service.action;

import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.entity.ChatSession;
import com.dalai.llama.chat.service.client.TrendIntelligenceServiceClient;
import com.dalai.llama.chat.service.client.TrendIntelligenceServiceClient.GenerateTrendReportRequest;
import com.dalai.llama.chat.service.client.TrendIntelligenceServiceClient.TrendReportView;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** No scope required -- always creates a brand-new report, so there's no existing-resource id to
 * protect against. {@code topic} is the only required model-extracted parameter. */
@Component
class GenerateTrendReportActionExecutor implements ChatActionExecutor {

    private final TrendIntelligenceServiceClient trendIntelligenceServiceClient;

    GenerateTrendReportActionExecutor(TrendIntelligenceServiceClient trendIntelligenceServiceClient) {
        this.trendIntelligenceServiceClient = trendIntelligenceServiceClient;
    }

    @Override
    public ChatActionType actionType() {
        return ChatActionType.GENERATE_TREND_REPORT;
    }

    @Override
    public ActionExecutionResult execute(UUID tenantId, ChatSession session, Map<String, String> parameters) {
        String topic = parameters == null ? null : parameters.get("topic");
        if (topic == null || topic.isBlank()) {
            return ActionExecutionResult.failure("I couldn't tell what topic you want a trend report on -- could you name it explicitly?");
        }
        TrendReportView report = trendIntelligenceServiceClient.generate(tenantId.toString(),
                new GenerateTrendReportRequest(topic, parameters.get("industry"), parameters.get("targetAudience")));
        String titles = report.predictions() == null || report.predictions().isEmpty()
                ? "(no predictions returned)"
                : report.predictions().stream().map(p -> p.title()).collect(Collectors.joining(", "));
        return ActionExecutionResult.success("Generated a trend report on \"" + report.topic() + "\" (id " + report.id()
                + ") with predictions: " + titles);
    }
}
