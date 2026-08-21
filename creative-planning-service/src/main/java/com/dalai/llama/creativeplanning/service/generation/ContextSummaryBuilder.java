package com.dalai.llama.creativeplanning.service.generation;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningMessage;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;

import java.util.List;
import java.util.stream.Collectors;

/** The one place brand/product/conversation context gets turned into prompt-ready text --
 * {@code ReferenceImageAnalysisService}, {@code CampaignPlanningChatService}, and {@code
 * LockedIdeaService} all need the same summaries, so this is the single owner of that
 * formatting rather than three ad-hoc copies. */
public final class ContextSummaryBuilder {

    private ContextSummaryBuilder() {
    }

    public static String brandSummary(BrandContext brand) {
        if (brand == null) {
            return "(no brand context set)";
        }
        return "Brand: %s. Industry: %s. Voice: %s. Target audience: %s. Values: %s".formatted(
                orNone(brand.getBrandName()), orNone(brand.getIndustry()), orNone(brand.getBrandVoice()),
                orNone(brand.getTargetAudience()), orNone(brand.getBrandValues()));
    }

    public static String productSummary(ProductProfile product) {
        if (product == null) {
            return "(no specific product -- brand-level campaign)";
        }
        return "Product: %s. Category: %s. Description: %s".formatted(
                orNone(product.getName()), orNone(product.getCategory()), orNone(product.getDescription()));
    }

    public static String conversationHistory(List<CampaignPlanningMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no messages yet)";
        }
        return messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "(not specified)" : value;
    }
}
