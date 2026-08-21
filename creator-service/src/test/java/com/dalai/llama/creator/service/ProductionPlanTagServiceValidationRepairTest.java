package com.dalai.llama.creator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPlanTagServiceValidationRepairTest {

    private final ProductionPlanTagService service = new ProductionPlanTagService(
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            null,
            null,
            null
    );

    @Test
    @SuppressWarnings("unchecked")
    void suppliesBackendOwnedTimingPolicyWhenProviderOmitsIt() {
        Map<String, Object> providerTag = new LinkedHashMap<>(Map.of(
                "projectTitle", "Mmmelt Noir",
                "shotTitle", "Abstract Luxury Texture Reveal"
        ));
        Map<String, Object> schemaReference = new LinkedHashMap<>(providerTag);
        schemaReference.put("maxClipSeconds", 15);
        schemaReference.put("maxDialogueSecondsPerShot", 14);
        schemaReference.put("dialogueTimingPolicy", "Split longer dialogue without dropping words.");

        Map<String, Object> repaired = ReflectionTestUtils.invokeMethod(
                service,
                "hydrateRequiredProductionMetadata",
                "storyboardTag",
                providerTag,
                schemaReference
        );

        assertThat(repaired)
                .containsEntry("maxClipSeconds", 15)
                .containsEntry("maxDialogueSecondsPerShot", 14)
                .containsEntry("dialogueTimingPolicy", "Split longer dialogue without dropping words.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacesShortenedGearCardListWithCompleteLocalPlan() {
        List<Map<String, Object>> providerCards = gearCards(5);
        List<Map<String, Object>> referenceCards = gearCards(6);
        Map<String, Object> providerTag = new LinkedHashMap<>();
        providerTag.put("gearCards", providerCards);
        providerTag.put("buildSteps", gearCards(6));
        Map<String, Object> schemaReference = new LinkedHashMap<>();
        schemaReference.put("gearCards", referenceCards);
        schemaReference.put("buildSteps", gearCards(6));

        Map<String, Object> repaired = ReflectionTestUtils.invokeMethod(
                service,
                "hydrateRequiredProductionMetadata",
                "lightingBuildSheetTag",
                providerTag,
                schemaReference
        );

        assertThat((List<Map<String, Object>>) repaired.get("gearCards"))
                .hasSize(6)
                .isEqualTo(referenceCards);
    }

    private List<Map<String, Object>> gearCards(int count) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            cards.add(Map.of("cardNumber", index, "title", "Card " + index));
        }
        return cards;
    }
}
