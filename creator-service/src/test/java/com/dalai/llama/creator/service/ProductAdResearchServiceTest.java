package com.dalai.llama.creator.service;

import com.dalai.llama.creator.dto.request.GenerateProductAdPipelineRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductAdResearchServiceTest {

    @Test
    void autoPlansProductOnlyBeverageShotsWithProductionPrompts() {
        CreatorAiService creatorAiService = mock(CreatorAiService.class);
        CreatorCreativeLearningService creativeLearningService = mock(CreatorCreativeLearningService.class);
        when(creatorAiService.generateMetered(any(), any(), any())).thenThrow(new RuntimeException("AI disabled for test"));
        when(creativeLearningService.approvedGuidance(any(), any(), any(), any(), any())).thenReturn(List.of());
        ProductAdResearchService service = new ProductAdResearchService(
                creatorAiService,
                creativeLearningService,
                new ObjectMapper(),
                WebClient.builder()
        );
        GenerateProductAdPipelineRequest request = new GenerateProductAdPipelineRequest(
                null,
                "Sparkling water bottle",
                List.of("https://example.com/sparkling-water.png"),
                "Increase purchases",
                "Urban adults",
                "Carbonated water, natural lime flavour",
                "bold premium",
                "beverage",
                "instagram_reels",
                30,
                3,
                8,
                "vertical",
                "FAST",
                "google",
                "imagen",
                true,
                true,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                Map.of("shotRecipeSource", "auto_product"),
                Map.of(),
                null
        );

        Map<String, Object> plan = service.buildProductAdPlan(request, "tenant", "user", null);

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(creatorAiService).generateMetered(eq("PRODUCT_AD_RESEARCH"), inputCaptor.capture(), any());
        assertThat(inputCaptor.getValue())
                .containsEntry("attachReferenceImages", true)
                .containsEntry("ingredientDetails", "Carbonated water, natural lime flavour");
        assertThat(stringList(inputCaptor.getValue().get("referenceImageUrls")))
                .containsExactly("https://example.com/sparkling-water.png");
        assertThat(String.valueOf(inputCaptor.getValue().get("renderedPrompt")))
                .contains(
                        "Select the ad tone yourself",
                        "Inspect every attached product image",
                        "binding creative requirements",
                        "campaignObjective",
                        "targetAudience"
                );

        assertThat(plan.get("noHumans")).isEqualTo(true);
        assertThat(plan.get("shotPlanningMode")).isEqualTo("AUTO_PRODUCT_AWARE");
        assertThat(plan.get("adTone")).isEqualTo("bold premium");
        assertThat(mapValue(plan.get("productIntelligence")))
                .containsEntry("ingredients", "Carbonated water, natural lime flavour")
                .containsEntry("targetAudience", "Urban adults")
                .containsEntry("toneSelectionMode", "AUTO_FROM_PRODUCT_CONTEXT");
        assertThat(stringList(plan.get("selectedShotTypes")))
                .contains("Hero Shot", "Pour Shot", "Splash Shot", "Macro Shot", "Pack Shot")
                .doesNotContain("Action Shot", "Lifestyle Shot");

        List<Map<String, Object>> shots = mapList(plan.get("shotPlan"));
        assertThat(shots).withFailMessage("Generated plan did not contain shots: %s", plan).isNotEmpty().allSatisfy(shot -> {
            assertThat(shot.get("noHumans")).isEqualTo(true);
            assertThat(String.valueOf(shot.get("negativePrompt"))).contains("no people", "no hands");
            assertThat(String.valueOf(shot.get("imagePrompt"))).isNotBlank();
            assertThat(String.valueOf(shot.get("videoPrompt"))).isNotBlank();
            assertThat(String.valueOf(shot.get("providerPrompt"))).isEqualTo(String.valueOf(shot.get("videoPrompt")));
            assertThat(String.valueOf(shot.get("cameraMovement"))).isNotBlank();
            assertThat(shot.get("speakerVisibility")).isEqualTo("OFF_SCREEN");
            assertThat(shot.get("lipSyncRequired")).isEqualTo(false);
        });

        Map<String, Object> dialoguePlan = mapValue(plan.get("dialoguePlan"));
        assertThat(dialoguePlan.get("dialogueMode")).isEqualTo("OFF_SCREEN_VOICEOVER");
        assertThat(dialoguePlan.get("lipSyncRequired")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
