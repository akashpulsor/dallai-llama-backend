package com.dalai.llama.chat.service;

import com.dalai.llama.chat.domain.ChatScopeType;

/** The one place the model-facing description of "what chat can actually do" is written --
 * scoped down to only the actions valid for a given session, so the model is never told it can
 * export/revise a plan in a session that isn't bound to one. */
final class ActionCatalog {

    private ActionCatalog() {
    }

    static String describe(ChatScopeType scopeType) {
        StringBuilder sb = new StringBuilder();
        sb.append("- GENERATE_TREND_REPORT: creates a new trend report. Parameters: topic (required, string), ")
                .append("industry (optional, string), targetAudience (optional, string).\n");
        if (scopeType == ChatScopeType.MARKETING_PLAN) {
            sb.append("- EXPORT_MARKETING_PLAN_PDF: exports the marketing plan this chat is about as a PDF. No parameters.\n");
            sb.append("- REVISE_MARKETING_PLAN: revises the marketing plan this chat is about. Parameters: instructions ")
                    .append("(required, string -- the specific change requested).\n");
        }
        return sb.toString();
    }
}
