package com.dalai.llama.chat.service;

import com.dalai.llama.chat.domain.ChatScopeType;
import com.dalai.llama.chat.service.client.PreProductionServiceClient;
import com.dalai.llama.chat.service.client.SuggestionTargetTypeView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** The one place the model-facing description of "what chat can actually do" is written --
 * scoped down to only the actions valid for a given session, so the model is never told it can
 * export/revise a plan in a session that isn't bound to one. PRE_PRODUCTION_PROJECT's valid
 * targetType values are NOT hardcoded here -- they come live from pre-production-service's own
 * {@code suggestion_target_type} master table (see {@link PreProductionServiceClient}), so this
 * description can never drift from what that service actually supports. */
@Component
class ActionCatalog {

    private final PreProductionServiceClient preProductionServiceClient;

    ActionCatalog(PreProductionServiceClient preProductionServiceClient) {
        this.preProductionServiceClient = preProductionServiceClient;
    }

    String describe(ChatScopeType scopeType) {
        StringBuilder sb = new StringBuilder();
        sb.append("- GENERATE_TREND_REPORT: creates a new trend report. Parameters: topic (required, string), ")
                .append("industry (optional, string), targetAudience (optional, string).\n");
        if (scopeType == ChatScopeType.MARKETING_PLAN) {
            sb.append("- EXPORT_MARKETING_PLAN_PDF: exports the marketing plan this chat is about as a PDF. No parameters.\n");
            sb.append("- REVISE_MARKETING_PLAN: revises the marketing plan this chat is about. Parameters: instructions ")
                    .append("(required, string -- the specific change requested).\n");
        }
        if (scopeType == ChatScopeType.PRE_PRODUCTION_PROJECT) {
            appendSuggestPreProductionChange(sb);
        }
        return sb.toString();
    }

    private void appendSuggestPreProductionChange(StringBuilder sb) {
        List<SuggestionTargetTypeView> targetTypes = preProductionServiceClient.listSuggestionTargetTypes();
        if (targetTypes.isEmpty()) {
            return;
        }
        String types = targetTypes.stream().map(SuggestionTargetTypeView::code).collect(Collectors.joining(", "));
        String refHints = targetTypes.stream()
                .filter(t -> Boolean.TRUE.equals(t.requiresTargetRef()))
                .map(t -> t.code() + " needs targetRef formatted as " + t.targetRefHint())
                .collect(Collectors.joining("; "));
        sb.append("- SUGGEST_PRE_PRODUCTION_CHANGE: logs a requested change against one specific piece of this project ")
                .append("for the creator to review -- nothing regenerates immediately. Parameters: targetType (required, one of ")
                .append(types).append("), targetRef (required only for target types that need one -- ")
                .append(refHints.isEmpty() ? "none do" : refHints)
                .append("), note (required, string -- the specific change requested, in the user's own words).\n");
    }
}
