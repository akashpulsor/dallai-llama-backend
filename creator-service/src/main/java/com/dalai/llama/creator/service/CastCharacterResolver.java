package com.dalai.llama.creator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's cast-to-character matching cluster - previously the
 * plan's "CastCharacterResolutionService" row. Exclusively called from ProviderRequestBuilder
 * (owner.castCharactersForScene(...)), which stays unchanged - it already reaches
 * ScreenplayVideoService through owner for this, so the call site doesn't need to know the
 * implementation moved.
 *
 * <p>Pure logic, no repositories or I/O - a genuinely self-contained cluster with a single
 * external caller, unlike the three prior extractions. Same package, owner back-reference only
 * because that's the established pattern for this refactor's owner-scoped classes, not because
 * this one needs it (it takes no ScreenplayVideoService collaborators at all).
 */
final class CastCharacterResolver {

    List<Map<String, Object>> castCharactersForScene(Map<String, Object> scene, Map<String, Object> contextPayload) {
        List<Map<String, Object>> castMappings = mapListValue(firstValue(
                contextPayload.get("characterCastMappings"),
                contextPayload.get("castMappings")
        ));
        if (castMappings.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> shotPlanCharacters = shotPlanCharactersForScene(scene);
        return shotPlanCharacters.isEmpty()
                ? castCharactersForSceneByFreeText(scene, castMappings)
                : castCharactersForSceneByShotPlan(shotPlanCharacters, castMappings);
    }

    private List<Map<String, Object>> shotPlanCharactersForScene(Map<String, Object> scene) {
        Map<String, Object> storyboardTag = mapValue(scene.get("storyboardTag"));
        if (storyboardTag.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> characters = new ArrayList<>();
        characters.addAll(mapListValue(storyboardTag.get("primaryCharacters")));
        characters.addAll(mapListValue(storyboardTag.get("sideCharacters")));
        return characters;
    }

    /**
     * Preferred path: this shot's own director/DP character specs (storyboardTag's
     * primaryCharacters/sideCharacters) already carry assignedActorName, which
     * STORYBOARD_TAG_GENERATE itself resolves from characterCastMappings - so matching
     * storyCharacterName back against characterCastMappings.characterName here is an exact
     * structured match on the same source of truth, not a text guess. This is what lets us
     * tell the video model precisely which uploaded face belongs to which named character.
     */
    private List<Map<String, Object>> castCharactersForSceneByShotPlan(
            List<Map<String, Object>> shotPlanCharacters,
            List<Map<String, Object>> castMappings
    ) {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> character : shotPlanCharacters) {
            String storyCharacterName = stringValue(character.get("storyCharacterName"), "").trim();
            String assignedActorName = stringValue(character.get("assignedActorName"), "").trim();
            if (storyCharacterName.isBlank() && assignedActorName.isBlank()) {
                continue;
            }
            Map<String, Object> mapping = findCastMapping(castMappings, storyCharacterName, assignedActorName);
            if (mapping == null) {
                continue;
            }
            Map<String, Object> castPayload = firstMap(mapping.get("castPayload"));
            Map<String, Object> matchedCharacter = new LinkedHashMap<>();
            matchedCharacter.put("characterKey", mapping.get("characterKey"));
            matchedCharacter.put("characterName", storyCharacterName.isBlank() ? stringValue(mapping.get("characterName"), "") : storyCharacterName);
            matchedCharacter.put("characterRole", stringValue(mapping.get("characterRole"), ""));
            matchedCharacter.put("castDisplayName", stringValue(mapping.get("castDisplayName"), assignedActorName));
            matchedCharacter.put("referenceImageUrl", stringValue(castPayload.get("referenceImageUrl"), ""));
            putReferenceImageStorageLocation(matchedCharacter, castPayload);
            matchedCharacter.put("archetypeLabel", stringValue(character.get("archetypeLabel"), ""));
            matchedCharacter.put("distinguishingFeatures", stringValue(character.get("distinguishingFeatures"), ""));
            matchedCharacter.put("wardrobeThisShot", stringValue(character.get("wardrobeThisShot"), ""));
            matched.add(matchedCharacter);
        }
        return matched;
    }

    private Map<String, Object> findCastMapping(List<Map<String, Object>> castMappings, String storyCharacterName, String assignedActorName) {
        if (!storyCharacterName.isBlank()) {
            for (Map<String, Object> mapping : castMappings) {
                if (storyCharacterName.equalsIgnoreCase(stringValue(mapping.get("characterName"), "").trim())) {
                    return mapping;
                }
            }
        }
        if (!assignedActorName.isBlank()) {
            for (Map<String, Object> mapping : castMappings) {
                if (assignedActorName.equalsIgnoreCase(stringValue(mapping.get("castDisplayName"), "").trim())) {
                    return mapping;
                }
            }
        }
        return null;
    }

    /** Fallback for shots that never went through shot production planning - preserves today's behavior exactly. */
    private List<Map<String, Object>> castCharactersForSceneByFreeText(Map<String, Object> scene, List<Map<String, Object>> castMappings) {
        String sceneText = String.join(" ",
                stringValue(scene.get("characterDetail"), ""),
                stringValue(scene.get("characterDetails"), ""),
                stringValue(scene.get("character"), ""),
                stringValue(scene.get("characters"), ""),
                stringValue(scene.get("title"), ""),
                stringValue(scene.get("sceneDetail"), ""),
                stringValue(scene.get("action"), ""),
                stringValue(scene.get("dialogue"), "")
        ).toLowerCase(Locale.ROOT);
        if (sceneText.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> mapping : castMappings) {
            String characterName = stringValue(mapping.get("characterName"), "").trim();
            if (characterName.isBlank() || !sceneText.contains(characterName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Map<String, Object> castPayload = firstMap(mapping.get("castPayload"));
            Map<String, Object> matchedCharacter = new LinkedHashMap<>();
            matchedCharacter.put("characterKey", mapping.get("characterKey"));
            matchedCharacter.put("characterName", characterName);
            matchedCharacter.put("characterRole", stringValue(mapping.get("characterRole"), ""));
            matchedCharacter.put("castDisplayName", stringValue(mapping.get("castDisplayName"), characterName));
            matchedCharacter.put("referenceImageUrl", stringValue(castPayload.get("referenceImageUrl"), ""));
            putReferenceImageStorageLocation(matchedCharacter, castPayload);
            matched.add(matchedCharacter);
        }
        return matched;
    }

    /**
     * bucket/objectKey let the caller fetch this image straight from internal storage
     * (AssetStorageService, the same path product reference images already use) instead of an
     * HTTP GET to the public signed referenceImageUrl - added after a confirmed live incident
     * where that public URL fetch failed with a one-shot 403 during a brief infra blip, silently
     * dropping the cast face from that generation. bucket/objectKey live under
     * castPayload.attributes.referenceImage (confirmed against real production cast_payload
     * data) rather than at the top level alongside referenceImageUrl.
     */
    private void putReferenceImageStorageLocation(Map<String, Object> matchedCharacter, Map<String, Object> castPayload) {
        Map<String, Object> referenceImage = firstMap(firstMap(castPayload.get("attributes")).get("referenceImage"));
        matchedCharacter.put("referenceImageBucket", stringValue(referenceImage.get("bucket"), ""));
        matchedCharacter.put("referenceImageObjectKey", stringValue(referenceImage.get("objectKey"), ""));
    }
}
