package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptBeat;
import com.dalai.llama.creator.domain.entity.CreatorScriptCharacter;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import com.dalai.llama.creator.repository.CreatorScriptBeatRepository;
import com.dalai.llama.creator.repository.CreatorScriptCharacterRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ScriptStructureService {

    private final CreatorScriptBeatRepository beatRepository;
    private final CreatorScriptCharacterRepository characterRepository;
    private final CreatorScriptShotRepository shotRepository;
    private final ObjectMapper objectMapper;

    public ScriptStructureService(
            CreatorScriptBeatRepository beatRepository,
            CreatorScriptCharacterRepository characterRepository,
            CreatorScriptShotRepository shotRepository,
            ObjectMapper objectMapper
    ) {
        this.beatRepository = beatRepository;
        this.characterRepository = characterRepository;
        this.shotRepository = shotRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void syncFromStoryScript(CreatorScript script, GeneratedStoryScriptResponse.StoryScript storyScript) {
        if (script == null || script.getId() == null || storyScript == null) {
            return;
        }

        UUID scriptId = script.getId();
        beatRepository.deleteByScriptId(scriptId);
        characterRepository.deleteByScriptId(scriptId);
        beatRepository.flush();
        characterRepository.flush();

        OffsetDateTime now = OffsetDateTime.now();
        saveCharacters(script, storyScript.getCharacters(), now);
        saveBeats(script, storyScript.getBeats(), now);
    }

    @Transactional
    public void syncScreenplayShots(CreatorScript script, Map<String, Object> scriptPayload, List<Map<String, Object>> shots) {
        if (script == null || script.getId() == null) {
            return;
        }
        UUID scriptId = script.getId();
        shotRepository.deleteByScriptId(scriptId);
        shotRepository.flush();
        List<Map<String, Object>> safeShots = shots == null || shots.isEmpty() ? extractNestedShots(scriptPayload) : shots;
        OffsetDateTime now = OffsetDateTime.now();
        Set<Integer> usedShotNumbers = new HashSet<>();
        for (int index = 0; index < safeShots.size(); index++) {
            Map<String, Object> rawShot = safeShots.get(index) == null ? Map.of() : safeShots.get(index);
            Map<String, Object> shot = new LinkedHashMap<>(rawShot);
            int requestedShotNumber = integerValue(shot.get("shotNumber"), index + 1);
            int shotNumber = nextAvailableShotNumber(requestedShotNumber, usedShotNumbers);
            shot.put("shotNumber", shotNumber);
            shotRepository.save(CreatorScriptShot.builder()
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .scriptId(scriptId)
                    .lockedIdeaId(script.getLockedIdeaId())
                    .storyIdeaId(script.getStoryIdeaId())
                    .sequenceNumber(integerValue(shot.get("sequenceNumber"), null))
                    .sceneNumber(integerValue(shot.get("sceneNumber"), null))
                    .shotNumber(shotNumber)
                    .beatNumber(integerValue(shot.get("beatNumber"), null))
                    .beatTitle(stringValue(shot.get("beatTitle")))
                    .startTime(doubleValue(shot.get("startTime")))
                    .endTime(doubleValue(shot.get("endTime")))
                    .durationSeconds(doubleValue(shot.get("durationSeconds")))
                    .title(defaultString(stringValue(shot.get("title")), "Shot " + shotNumber))
                    .purpose(stringValue(shot.get("purpose")))
                    .shotType(stringValue(shot.get("shotType")))
                    .coverageType(stringValue(shot.get("coverageType")))
                    .screenDirection(stringValue(shot.get("screenDirection")))
                    .primaryCharacters(stringList(shot.get("primaryCharacters")))
                    .sideCharacters(stringList(shot.get("sideCharacters")))
                    .primaryActors(stringList(shot.get("primaryActors")))
                    .sideActors(stringList(shot.get("sideActors")))
                    .dialogue(mapValue(shot.get("dialogue")))
                    .shotPayload(new LinkedHashMap<>(shot))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    private int nextAvailableShotNumber(Integer requestedShotNumber, Set<Integer> usedShotNumbers) {
        int shotNumber = requestedShotNumber == null || requestedShotNumber <= 0 ? 1 : requestedShotNumber;
        while (usedShotNumbers.contains(shotNumber)) {
            shotNumber++;
        }
        usedShotNumbers.add(shotNumber);
        return shotNumber;
    }

    @Transactional(readOnly = true)
    public Optional<CreatorScriptCharacter> findScriptCharacter(UUID scriptId, String characterKey) {
        if (scriptId == null || characterKey == null || characterKey.isBlank()) {
            return Optional.empty();
        }
        return characterRepository.findByScriptIdAndCharacterKey(scriptId, characterKey);
    }

    private void saveCharacters(CreatorScript script, List<GeneratedStoryScriptResponse.CharacterProfile> characters, OffsetDateTime now) {
        List<GeneratedStoryScriptResponse.CharacterProfile> safeCharacters = characters == null ? List.of() : characters;
        Set<String> usedCharacterKeys = new HashSet<>();
        for (int index = 0; index < safeCharacters.size(); index++) {
            GeneratedStoryScriptResponse.CharacterProfile character = safeCharacters.get(index);
            Map<String, Object> payload = toMap(character);
            String key = defaultString(stringValue(payload.get("characterKey")), stringValue(payload.get("key")));
            if (key == null || key.isBlank()) {
                key = slugify(defaultString(character.getName(), "character")) + "-" + (index + 1);
            }
            key = nextAvailableKey(key, usedCharacterKeys);

            characterRepository.save(CreatorScriptCharacter.builder()
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .scriptId(script.getId())
                    .lockedIdeaId(script.getLockedIdeaId())
                    .storyIdeaId(script.getStoryIdeaId())
                    .characterKey(key)
                    .characterName(defaultString(character.getName(), "Character " + (index + 1)))
                    .characterRole(character.getRole())
                    .gender(character.getGender())
                    .age(character.getAge())
                    .ageRange(character.getAgeRange())
                    .look(character.getLook())
                    .profile(character.getProfile())
                    .persona(character.getPersona())
                    .backstory(character.getBackstory())
                    .motivation(character.getMotivation())
                    .fearOrBlock(character.getFearOrBlock())
                    .relationshipToStory(character.getRelationshipToStory())
                    .speakingStyle(character.getSpeakingStyle())
                    .visualIdentity(character.getVisualIdentity())
                    .characterPayload(payload)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    private void saveBeats(CreatorScript script, List<GeneratedStoryScriptResponse.StoryBeat> beats, OffsetDateTime now) {
        List<GeneratedStoryScriptResponse.StoryBeat> safeBeats = beats == null ? List.of() : beats;
        Set<Integer> usedBeatNumbers = new HashSet<>();
        for (int index = 0; index < safeBeats.size(); index++) {
            GeneratedStoryScriptResponse.StoryBeat beat = safeBeats.get(index);
            int requestedBeatNumber = beat.getBeatNumber() == null || beat.getBeatNumber() <= 0 ? index + 1 : beat.getBeatNumber();
            int beatNumber = nextAvailableNumber(requestedBeatNumber, usedBeatNumbers);
            beatRepository.save(CreatorScriptBeat.builder()
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .scriptId(script.getId())
                    .lockedIdeaId(script.getLockedIdeaId())
                    .storyIdeaId(script.getStoryIdeaId())
                    .beatNumber(beatNumber)
                    .title(defaultString(beat.getTitle(), "Beat " + beatNumber))
                    .summary(beat.getSummary())
                    .characterFocus(beat.getCharacterFocus())
                    .emotionalPurpose(beat.getEmotionalPurpose())
                    .estimatedSeconds(beat.getEstimatedSeconds())
                    .beatPayload(toMap(beat))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    private int nextAvailableNumber(Integer requestedNumber, Set<Integer> usedNumbers) {
        int number = requestedNumber == null || requestedNumber <= 0 ? 1 : requestedNumber;
        while (usedNumbers.contains(number)) {
            number++;
        }
        usedNumbers.add(number);
        return number;
    }

    private String nextAvailableKey(String requestedKey, Set<String> usedKeys) {
        String baseKey = defaultString(requestedKey, "character").trim();
        String key = baseKey;
        int suffix = 2;
        while (usedKeys.contains(key)) {
            key = baseKey + "-" + suffix;
            suffix++;
        }
        usedKeys.add(key);
        return key;
    }

    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<Map<String, Object>> extractNestedShots(Map<String, Object> scriptPayload) {
        if (scriptPayload == null || scriptPayload.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> direct = mapList(scriptPayload.get("shots"));
        if (!direct.isEmpty()) {
            return direct;
        }
        List<Map<String, Object>> flattened = new java.util.ArrayList<>();
        for (Map<String, Object> scene : mapList(scriptPayload.get("scenes"))) {
            flattened.addAll(mapList(scene.get("shots")));
        }
        for (Map<String, Object> sequence : mapList(scriptPayload.get("sequences"))) {
            for (Map<String, Object> scene : mapList(sequence.get("scenes"))) {
                flattened.addAll(mapList(scene.get("shots")));
            }
        }
        return flattened;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {
                }))
                .toList();
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private Integer integerValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Double.parseDouble(String.valueOf(value).replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String slugify(String value) {
        String slug = String.valueOf(value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "character" : slug;
    }
}
