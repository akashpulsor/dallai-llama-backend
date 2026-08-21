package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for the cast-to-character matching cluster moved out of
 * ScreenplayVideoService into CastCharacterResolver - previously 0 test coverage.
 */
class CastCharacterResolverTest {

    private final CastCharacterResolver resolver = new CastCharacterResolver();

    @Test
    void returnsEmpty_whenNoCastMappingsConfigured() {
        List<Map<String, Object>> result = resolver.castCharactersForScene(
                Map.of("title", "Riya opens the door"), Map.of()
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void shotPlanPath_matchesByStoryCharacterNameAgainstCastMapping() {
        Map<String, Object> castMapping = new LinkedHashMap<>();
        castMapping.put("characterKey", "riya-1");
        castMapping.put("characterName", "Riya");
        castMapping.put("characterRole", "lead");
        castMapping.put("castDisplayName", "Ananya");
        castMapping.put("castPayload", Map.of("referenceImageUrl", "https://cdn/riya.jpg"));

        Map<String, Object> shotPlanCharacter = new LinkedHashMap<>();
        shotPlanCharacter.put("storyCharacterName", "riya");
        shotPlanCharacter.put("archetypeLabel", "Confident professional");
        shotPlanCharacter.put("distinguishingFeatures", "Curly hair");
        shotPlanCharacter.put("wardrobeThisShot", "Blue kurti");

        Map<String, Object> storyboardTag = new LinkedHashMap<>();
        storyboardTag.put("primaryCharacters", List.of(shotPlanCharacter));

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("storyboardTag", storyboardTag);

        Map<String, Object> contextPayload = Map.of("characterCastMappings", List.of(castMapping));

        List<Map<String, Object>> result = resolver.castCharactersForScene(scene, contextPayload);

        assertEquals(1, result.size());
        Map<String, Object> matched = result.get(0);
        assertEquals("riya-1", matched.get("characterKey"));
        assertEquals("riya", matched.get("characterName"));
        assertEquals("Ananya", matched.get("castDisplayName"));
        assertEquals("https://cdn/riya.jpg", matched.get("referenceImageUrl"));
        assertEquals("Blue kurti", matched.get("wardrobeThisShot"));
    }

    @Test
    void shotPlanPath_fallsBackToAssignedActorNameWhenStoryCharacterNameDoesNotMatch() {
        Map<String, Object> castMapping = new LinkedHashMap<>();
        castMapping.put("characterKey", "actor-1");
        castMapping.put("characterName", "Unrelated Name");
        castMapping.put("castDisplayName", "Ananya Sharma");
        castMapping.put("castPayload", Map.of());

        Map<String, Object> shotPlanCharacter = new LinkedHashMap<>();
        shotPlanCharacter.put("storyCharacterName", "Someone Else");
        shotPlanCharacter.put("assignedActorName", "Ananya Sharma");

        Map<String, Object> scene = Map.of("storyboardTag", Map.of("primaryCharacters", List.of(shotPlanCharacter)));
        Map<String, Object> contextPayload = Map.of("characterCastMappings", List.of(castMapping));

        List<Map<String, Object>> result = resolver.castCharactersForScene(scene, contextPayload);

        assertEquals(1, result.size());
        assertEquals("actor-1", result.get(0).get("characterKey"));
    }

    @Test
    void freeTextPath_usedWhenSceneHasNoShotPlanCharacters() {
        Map<String, Object> castMapping = new LinkedHashMap<>();
        castMapping.put("characterKey", "riya-1");
        castMapping.put("characterName", "Riya");
        castMapping.put("castPayload", Map.of("referenceImageUrl", "https://cdn/riya.jpg"));

        Map<String, Object> scene = Map.of("action", "Riya walks into the room and smiles.");
        Map<String, Object> contextPayload = Map.of("castMappings", List.of(castMapping));

        List<Map<String, Object>> result = resolver.castCharactersForScene(scene, contextPayload);

        assertEquals(1, result.size());
        assertEquals("Riya", result.get(0).get("characterName"));
        assertEquals("https://cdn/riya.jpg", result.get(0).get("referenceImageUrl"));
    }

    /**
     * Regression tests for referenceImageBucket/referenceImageObjectKey - added after a
     * confirmed live incident where fetching a cast face via the public signed referenceImageUrl
     * failed with a one-shot 403 during a brief infra blip, silently dropping the cast face from
     * that generation. Real production cast_payload data (script "The Colors of Jaipur: Your New
     * Kurti") confirmed bucket/objectKey live under castPayload.attributes.referenceImage, not
     * at the top level alongside referenceImageUrl.
     */
    @Test
    void shotPlanPath_extractsReferenceImageBucketAndObjectKeyFromNestedAttributes() {
        Map<String, Object> castPayload = Map.of(
                "referenceImageUrl", "https://media.dalaillama.in/creator-assets/cast-profiles/x/reference.jpg?signed",
                "attributes", Map.of(
                        "referenceImage", Map.of(
                                "bucket", "creator-assets",
                                "objectKey", "tenant/user/cast-profiles/x/reference.jpg",
                                "contentType", "image/jpeg"
                        )
                )
        );
        Map<String, Object> castMapping = new LinkedHashMap<>();
        castMapping.put("characterKey", "anaya-2");
        castMapping.put("characterName", "Anaya");
        castMapping.put("castDisplayName", "Tanvi");
        castMapping.put("castPayload", castPayload);

        Map<String, Object> shotPlanCharacter = new LinkedHashMap<>();
        shotPlanCharacter.put("storyCharacterName", "Anaya");
        shotPlanCharacter.put("assignedActorName", "Tanvi");

        Map<String, Object> scene = Map.of("storyboardTag", Map.of("primaryCharacters", List.of(shotPlanCharacter)));
        Map<String, Object> contextPayload = Map.of("characterCastMappings", List.of(castMapping));

        List<Map<String, Object>> result = resolver.castCharactersForScene(scene, contextPayload);

        assertEquals(1, result.size());
        assertEquals("creator-assets", result.get(0).get("referenceImageBucket"));
        assertEquals("tenant/user/cast-profiles/x/reference.jpg", result.get(0).get("referenceImageObjectKey"));
    }

    @Test
    void shotPlanPath_referenceImageBucketAndObjectKeyBlankWhenAttributesMissing() {
        Map<String, Object> castMapping = new LinkedHashMap<>();
        castMapping.put("characterKey", "riya-1");
        castMapping.put("characterName", "Riya");
        castMapping.put("castPayload", Map.of("referenceImageUrl", "https://cdn/riya.jpg"));

        Map<String, Object> shotPlanCharacter = new LinkedHashMap<>();
        shotPlanCharacter.put("storyCharacterName", "Riya");

        Map<String, Object> scene = Map.of("storyboardTag", Map.of("primaryCharacters", List.of(shotPlanCharacter)));
        Map<String, Object> contextPayload = Map.of("characterCastMappings", List.of(castMapping));

        List<Map<String, Object>> result = resolver.castCharactersForScene(scene, contextPayload);

        assertEquals(1, result.size());
        assertEquals("", result.get(0).get("referenceImageBucket"));
        assertEquals("", result.get(0).get("referenceImageObjectKey"));
    }

    @Test
    void freeTextPath_returnsEmptyWhenNoCharacterNameAppearsInSceneText() {
        Map<String, Object> castMapping = Map.of("characterName", "Riya");
        Map<String, Object> scene = Map.of("action", "The camera pans across an empty street.");
        Map<String, Object> contextPayload = Map.of("castMappings", List.of(castMapping));

        List<Map<String, Object>> result = resolver.castCharactersForScene(scene, contextPayload);

        assertTrue(result.isEmpty());
    }
}
