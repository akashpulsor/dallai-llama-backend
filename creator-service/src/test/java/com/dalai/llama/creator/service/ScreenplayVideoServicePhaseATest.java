package com.dalai.llama.creator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Characterization test for a bug fixed this session, pinned here since ScreenplayVideoService
 * had zero coverage of this path before: a product-led shot with an assigned cast member (a model
 * wearing/using the product) could never get Seedance's product-CGI multi-reference mode, because
 * seedanceProductReferenceMode was gated on noHumans==true - which is false the instant a cast
 * character is present - so the product frame was silently dropped and only the raw cast photo
 * was sent. Fixed by decoupling that flag from noHumans; castFaceReferenceMode is independent and
 * can now be true at the same time as product-reference mode.
 *
 * <p>The companion bug (chat-edit reverted on regenerate) and its test moved to
 * screenplayvideo/ShotPlanTagGatewayImplTest once persistChatEditedTonePlan/attachProductionPlanTags
 * became that class's public methods.
 */
class ScreenplayVideoServicePhaseATest {

    @Test
    void buildProviderRequestForScene_combinesProductFrameAndCastFaceForProductLedSceneWithCast() throws Exception {
        ScreenplayVideoService service = mock(
                ScreenplayVideoService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS)
        );
        setField(service, "objectMapper", new ObjectMapper());

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("id", "scene-1");
        scene.put("sceneNumber", 1);
        // Free-text cast match: the character's name appears in the scene text.
        scene.put("title", "Riya shows off the new kurti");
        scene.put("action", "Riya twirls, showing the kurti's fabric drape.");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("productLed", true);
        request.put("imageLedAdMode", true);
        request.put("noHumans", false); // a real product-led shot WITH a model - this used to disable product mode entirely
        request.put("generatedProductImageUrl", "https://example.com/generated-frame.jpg");
        request.put("canonicalProductImageUrls", List.of("https://example.com/canonical-product.jpg"));
        request.put("maxClipSeconds", 8);

        Map<String, Object> castMapping = new LinkedHashMap<>();
        castMapping.put("characterName", "Riya");
        castMapping.put("castDisplayName", "Riya (Model)");
        castMapping.put("castPayload", Map.of("referenceImageUrl", "https://example.com/riya-face.jpg"));
        Map<String, Object> contextPayload = new LinkedHashMap<>();
        contextPayload.put("characterCastMappings", List.of(castMapping));

        Method method = ScreenplayVideoService.class.getDeclaredMethod(
                "buildProviderRequestForScene", String.class, String.class, Map.class, Map.class, Map.class
        );
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> providerRequest = (Map<String, Object>) method.invoke(
                service, "seedance", "seedance-v1", scene, request, contextPayload
        );

        assertTrue((Boolean) providerRequest.get("seedanceReferenceToVideo"),
                "product-led scene with a cast member must still use product-reference mode");
        assertTrue((Boolean) providerRequest.get("castFaceReferenceMode"),
                "a cast member's face must still be attached even in product-reference mode");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> castFaces = (List<Map<String, Object>>) providerRequest.get("castFaces");
        assertFalse(castFaces.isEmpty(), "cast face reference should be resolved for the matched character");
        assertEquals("https://example.com/riya-face.jpg", castFaces.get(0).get("referenceImageUrl"));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = ScreenplayVideoService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
