package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class ScreenplayVideoAiScenePolicyTest {

    @Test
    void leavesAvatarDialogueUntouched() throws Exception {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("dialogueScript", "Original avatar dialogue");
        scene.put("dialogueLanguage", "Hinglish");

        Map<String, Object> localized = invokeLocalization(
                scene,
                Map.of("dialogueLanguage", "English", "languageCode", "en-IN", "autoTranslateDialogue", true),
                "talking_head"
        );

        assertEquals("Original avatar dialogue", localized.get("dialogueScript"));
        assertEquals("Hinglish", localized.get("dialogueLanguage"));
        assertFalse(localized.containsKey("dialogueLocalizationStatus"));
    }

    @Test
    void restoresCanonicalDialogueWhenAiSceneReturnsToSourceLanguage() throws Exception {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("dialogueScript", "Abhi translated line");
        scene.put("dialogueLanguage", "Hinglish");
        scene.put("sourceDialogueScript", "This is the canonical English line.");
        scene.put("sourceDialogueLanguage", "English");

        Map<String, Object> localized = invokeLocalization(
                scene,
                Map.of("dialogueLanguage", "English", "languageCode", "en-IN", "autoTranslateDialogue", true),
                "ai_generated"
        );

        assertEquals("This is the canonical English line.", localized.get("dialogueScript"));
        assertEquals("English", localized.get("dialogueLanguage"));
        assertEquals("en-IN", localized.get("languageCode"));
        assertEquals("RESTORED_SOURCE", localized.get("dialogueLocalizationStatus"));
    }

    @Test
    void createsPerShotCgiPlanOnlyForNoHumanProductFlow() throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("shotNumber", 1);
        first.put("shotType", "Macro Shot");
        first.put("action", "Condensation beads collect on the bottle while it rotates.");
        first.put("cameraMovement", "Slow 20-degree orbit");
        first.put("noHumans", true);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("shotNumber", 2);
        second.put("shotType", "Hero Packshot");
        second.put("action", "The bottle settles on a reflective pedestal.");
        second.put("noHumans", true);

        Map<String, Object> planned = invokeProductPlan(
                first,
                List.of(first, second),
                Map.of(
                        "noHumans", true,
                        "productIntelligence", Map.of(
                                "productName", "Client Bottle",
                                "ingredients", List.of("green tea", "lemon"),
                                "benefits", List.of("refreshing taste"),
                                "approvedClaims", List.of("no added sugar"),
                                "targetAudience", "Busy urban professionals seeking healthier refreshment",
                                "campaignObjective", "Show that the drink makes a better everyday replacement for sugary soda."
                        )
                ),
                Map.of(
                        "productLed", true,
                        "noHumans", true,
                        "imageLedAdMode", true
                )
        );

        assertEquals(true, planned.get("productCgiScene"));
        assertEquals(true, planned.get("noHumans"));
        assertTrue(String.valueOf(planned.get("productImagePrompt")).contains("Client Bottle"));
        assertTrue(String.valueOf(planned.get("productImagePrompt")).contains("Hero Packshot"));
        assertTrue(String.valueOf(planned.get("productImagePrompt")).contains("green tea"));
        assertTrue(String.valueOf(planned.get("productImagePrompt")).contains("no added sugar"));
        assertTrue(String.valueOf(planned.get("productImagePrompt")).contains("Busy urban professionals"));
        assertTrue(String.valueOf(planned.get("videoMotionPrompt")).contains("better everyday replacement"));
        assertTrue(String.valueOf(planned.get("productImagePrompt")).contains("never invent an ingredient"));
        assertTrue(String.valueOf(planned.get("videoMotionPrompt")).contains("Slow 20-degree orbit"));
        assertTrue(String.valueOf(planned.get("videoMotionPrompt")).contains("Grounded product evidence"));
        assertTrue(((Map<?, ?>) planned.get("productCreativeEvidence")).containsKey("ingredientsOrMaterials"));
        assertEquals(
                "Busy urban professionals seeking healthier refreshment",
                ((Map<?, ?>) planned.get("productCreativeEvidence")).get("targetAudience")
        );
        assertEquals(
                "Show that the drink makes a better everyday replacement for sugary soda.",
                ((Map<?, ?>) planned.get("productCreativeEvidence")).get("campaignObjective")
        );
        assertTrue(String.valueOf(planned.get("negativePrompt")).contains("no people"));
        assertTrue(String.valueOf(((Map<?, ?>) planned.get("productShotPlan")).get("seedanceInputPolicy")).contains("@Image1"));

        Map<String, Object> ordinaryStory = invokeProductPlan(
                Map.of("shotNumber", 1, "action", "A person enters the room."),
                List.of(Map.of("shotNumber", 1, "action", "A person enters the room.")),
                Map.of(),
                Map.of("productLed", false, "noHumans", false)
        );
        assertFalse(ordinaryStory.containsKey("productCgiScene"));
        assertFalse(ordinaryStory.containsKey("productShotPlan"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeLocalization(
            Map<String, Object> scene,
            Map<String, Object> request,
            String generationMode
    ) throws Exception {
        ScreenplayVideoService service = mock(
                ScreenplayVideoService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS)
        );
        Field objectMapperField = ScreenplayVideoService.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(service, new ObjectMapper());
        Method method = ScreenplayVideoService.class.getDeclaredMethod(
                "localizeAiSceneForGeneration",
                CreatorScript.class,
                Map.class,
                Map.class,
                Map.class,
                String.class,
                UUID.class
        );
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(
                service,
                null,
                scene,
                Map.of(),
                request,
                generationMode,
                UUID.randomUUID()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeProductPlan(
            Map<String, Object> scene,
            List<Map<String, Object>> scenes,
            Map<String, Object> scriptPayload,
            Map<String, Object> request
    ) throws Exception {
        ScreenplayVideoService service = mock(
                ScreenplayVideoService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS)
        );
        Field objectMapperField = ScreenplayVideoService.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(service, new ObjectMapper());
        Method method = ScreenplayVideoService.class.getDeclaredMethod(
                "enrichProductCgiScenePlan",
                Map.class,
                List.class,
                int.class,
                Map.class,
                Map.class,
                String.class
        );
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(
                service,
                scene,
                scenes,
                0,
                scriptPayload,
                request,
                "Preserve the original label exactly."
        );
    }
}
