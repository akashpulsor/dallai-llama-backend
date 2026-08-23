package com.dalai.llama.chat.service.action;

import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.ChatScopeType;
import com.dalai.llama.chat.domain.entity.ChatSession;
import com.dalai.llama.chat.service.client.PreProductionServiceClient;
import com.dalai.llama.chat.service.client.PreProductionServiceClient.SuggestChangeRequest;
import com.dalai.llama.chat.service.client.SuggestionTargetTypeView;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Requires the session to be bound to a locked pre-production project -- same id-from-scope-only
 * rule as the marketing-plan executors: the project id always comes from {@code
 * session.getScopeId()}, never from anything the model said. {@code targetType}/{@code targetRef}/
 * {@code note} are model-extracted; this executor validates {@code targetType} against pre-
 * production-service's own master table (never a hardcoded set here -- see {@link
 * PreProductionServiceClient#listSuggestionTargetTypes}) and requires {@code targetRef} exactly
 * when that table says the type needs one; everything else (does the ref actually resolve to a
 * real shot, what the change means) is pre-production-service's job. */
@Component
class SuggestPreProductionChangeActionExecutor implements ChatActionExecutor {

    private final PreProductionServiceClient preProductionServiceClient;

    SuggestPreProductionChangeActionExecutor(PreProductionServiceClient preProductionServiceClient) {
        this.preProductionServiceClient = preProductionServiceClient;
    }

    @Override
    public ChatActionType actionType() {
        return ChatActionType.SUGGEST_PRE_PRODUCTION_CHANGE;
    }

    @Override
    public ActionExecutionResult execute(UUID tenantId, ChatSession session, Map<String, String> parameters) {
        if (session.getScopeType() != ChatScopeType.PRE_PRODUCTION_PROJECT || session.getScopeId() == null) {
            return ActionExecutionResult.failure("This chat isn't attached to a locked project, so there's nothing for me to suggest a change to.");
        }
        String targetTypeParam = parameters == null ? null : parameters.get("targetType");
        Optional<SuggestionTargetTypeView> targetType = targetTypeParam == null ? Optional.empty()
                : preProductionServiceClient.listSuggestionTargetTypes().stream()
                        .filter(t -> t.code().equalsIgnoreCase(targetTypeParam))
                        .findFirst();
        if (targetType.isEmpty()) {
            return ActionExecutionResult.failure("I couldn't tell which part of the project you want changed -- could you be more specific?");
        }
        String note = parameters == null ? null : parameters.get("note");
        if (note == null || note.isBlank()) {
            return ActionExecutionResult.failure("I couldn't tell exactly what change you want -- could you spell it out?");
        }
        String targetRef = parameters == null ? null : parameters.get("targetRef");
        if (Boolean.TRUE.equals(targetType.get().requiresTargetRef()) && (targetRef == null || targetRef.isBlank())) {
            return ActionExecutionResult.failure("Which one did you mean specifically? I need a bit more detail to log this.");
        }

        preProductionServiceClient.suggestChange(tenantId.toString(), session.getScopeId(),
                new SuggestChangeRequest(targetType.get().code(), targetRef, note));
        return ActionExecutionResult.success("Noted -- I've logged this as a suggested change for your creator to review and apply.");
    }
}
