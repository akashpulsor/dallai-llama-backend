package com.dalai.llama.chat.service.action;

import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.ChatScopeType;
import com.dalai.llama.chat.domain.entity.ChatSession;
import com.dalai.llama.chat.service.client.CreativePlanningServiceClient;
import com.dalai.llama.chat.service.client.CreativePlanningServiceClient.ReviseMarketingPlanRequest;
import com.dalai.llama.chat.service.client.CreativePlanningServiceClient.ReviseMarketingPlanResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Requires the session to be bound to a marketing plan -- same id-from-scope-only rule as {@link
 * ExportMarketingPlanPdfActionExecutor}. {@code instructions} is the one model-extracted
 * parameter, and it's exactly what the target endpoint expects: free-text guidance, not a
 * structural change the model would have to get exactly right. */
@Component
class ReviseMarketingPlanActionExecutor implements ChatActionExecutor {

    private final CreativePlanningServiceClient creativePlanningServiceClient;

    ReviseMarketingPlanActionExecutor(CreativePlanningServiceClient creativePlanningServiceClient) {
        this.creativePlanningServiceClient = creativePlanningServiceClient;
    }

    @Override
    public ChatActionType actionType() {
        return ChatActionType.REVISE_MARKETING_PLAN;
    }

    @Override
    public ActionExecutionResult execute(UUID tenantId, ChatSession session, Map<String, String> parameters) {
        if (session.getScopeType() != ChatScopeType.MARKETING_PLAN || session.getScopeId() == null) {
            return ActionExecutionResult.failure("This chat isn't attached to a marketing plan, so there's nothing for me to revise.");
        }
        String instructions = parameters == null ? null : parameters.get("instructions");
        if (instructions == null || instructions.isBlank()) {
            return ActionExecutionResult.failure("I couldn't tell exactly what change you want -- could you spell it out?");
        }
        ReviseMarketingPlanResponse response = creativePlanningServiceClient.reviseMarketingPlan(
                tenantId.toString(), session.getScopeId(), new ReviseMarketingPlanRequest(instructions));
        return ActionExecutionResult.success("Revised the plan per your instructions and re-ran the review harness (session "
                + response.critiqueSessionId() + ").");
    }
}
