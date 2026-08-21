package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import com.dalai.llama.critic.dto.shotcontext.Technical;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.critic.service.llmgateway.ModelCapabilityView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Level 4 of the critic hierarchy: "can the selected generation model actually execute this shot
 * reliably?" Deterministic -- no LLM call, just a lookup against llm-gateway's {@code
 * model_capability} table plus a small, honest set of heuristics (not an exhaustive rules
 * engine). Skips entirely if the plan has no {@code Technical.targetModel} pinned yet, or if that
 * model has no capability rows registered -- there's nothing to assess feasibility against.
 * Findings here are always P2 (advisory): a capability mismatch is a real risk signal, not proof
 * the shot will fail.
 */
@Component
class GenerationFeasibilityCritic implements ShotCritic {

    private static final int LONG_SHOT_THRESHOLD_SECONDS = 6;

    private final LlmGatewayClient llmGatewayClient;

    GenerationFeasibilityCritic(LlmGatewayClient llmGatewayClient) {
        this.llmGatewayClient = llmGatewayClient;
    }

    @Override
    public CriticRole role() {
        return CriticRole.GENERATION_FEASIBILITY;
    }

    @Override
    public List<CriticFindingItem> critique(UUID tenantId, ShotContext plan) {
        Technical technical = plan.technical();
        String targetModel = technical == null ? null : technical.targetModel();
        if (targetModel == null || targetModel.isBlank()) {
            return List.of();
        }

        List<ModelCapabilityView> capabilities = llmGatewayClient.listModelCapabilities(tenantId.toString(), targetModel);
        if (capabilities.isEmpty()) {
            return List.of();
        }
        Map<String, String> strengthByCapability = capabilities.stream()
                .collect(Collectors.toMap(ModelCapabilityView::capabilityKey, ModelCapabilityView::strength, (a, b) -> a));

        List<CriticFindingItem> findings = new ArrayList<>();
        checkLongContinuousMovement(plan, targetModel, strengthByCapability, findings);
        checkProductConsistency(plan, targetModel, strengthByCapability, findings);
        return findings;
    }

    private void checkLongContinuousMovement(ShotContext plan, String targetModel, Map<String, String> strengths, List<CriticFindingItem> findings) {
        Integer duration = plan.technical() == null ? null : plan.technical().durationSeconds();
        String movementType = plan.camera() == null ? null : plan.camera().movementType();
        boolean isMoving = movementType != null && !movementType.isBlank() && !"static".equalsIgnoreCase(movementType.trim());
        boolean isLong = duration != null && duration > LONG_SHOT_THRESHOLD_SECONDS;
        if (isMoving && isLong && "WEAK".equalsIgnoreCase(strengths.get("LONG_CONTINUOUS_SHOTS"))) {
            findings.add(new CriticFindingItem(
                    "Shot is " + duration + "s with continuous camera movement (\"" + movementType + "\"), targeting model " + targetModel + ".",
                    "The model is registered WEAK at LONG_CONTINUOUS_SHOTS -- this shot may lose coherence or drift partway through.",
                    "Long duration combined with sustained camera movement is exactly the case that capability is weak at.",
                    "Shorten the shot, simplify the movement to a shorter beat, or split into two shots joined by a cut.",
                    "P2"));
        }
    }

    private void checkProductConsistency(ShotContext plan, String targetModel, Map<String, String> strengths, List<CriticFindingItem> findings) {
        boolean isProductHero = plan.productBrand() != null && Boolean.TRUE.equals(plan.productBrand().isProductHeroShot());
        if (isProductHero && "WEAK".equalsIgnoreCase(strengths.get("OBJECT_CONSISTENCY"))) {
            findings.add(new CriticFindingItem(
                    "This is a product-hero shot targeting model " + targetModel + ".",
                    "The model is registered WEAK at OBJECT_CONSISTENCY -- the product's shape, logo, or proportions may drift or distort.",
                    "Product-hero shots depend entirely on the product rendering consistently, which is exactly what this capability measures.",
                    "Prefer a model with stronger OBJECT_CONSISTENCY for this shot, or keep the product static and simple in frame.",
                    "P2"));
        }
    }
}
