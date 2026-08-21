package com.dalai.llama.chat.service.action;

import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.ChatScopeType;
import com.dalai.llama.chat.domain.entity.ChatSession;
import com.dalai.llama.chat.service.client.CreativePlanningServiceClient;
import com.dalai.llama.chat.service.client.CreativePlanningServiceClient.BrandPlanExportView;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Requires the session to be bound to a marketing plan -- the plan id it acts on always comes
 * from {@code session.getScopeId()}, never from anything the model said. */
@Component
class ExportMarketingPlanPdfActionExecutor implements ChatActionExecutor {

    private final CreativePlanningServiceClient creativePlanningServiceClient;

    ExportMarketingPlanPdfActionExecutor(CreativePlanningServiceClient creativePlanningServiceClient) {
        this.creativePlanningServiceClient = creativePlanningServiceClient;
    }

    @Override
    public ChatActionType actionType() {
        return ChatActionType.EXPORT_MARKETING_PLAN_PDF;
    }

    @Override
    public ActionExecutionResult execute(UUID tenantId, ChatSession session, Map<String, String> parameters) {
        if (session.getScopeType() != ChatScopeType.MARKETING_PLAN || session.getScopeId() == null) {
            return ActionExecutionResult.failure("This chat isn't attached to a marketing plan, so there's nothing for me to export.");
        }
        BrandPlanExportView export = creativePlanningServiceClient.exportMarketingPlanPdf(tenantId.toString(), session.getScopeId());
        return ActionExecutionResult.success("Exported the plan to PDF: " + export.signedUrl());
    }
}
