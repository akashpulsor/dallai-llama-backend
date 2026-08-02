package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAvatarSceneDialogue;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.dto.internal.AvatarScreenplayDocument;
import com.dalai.llama.creator.repository.CreatorAvatarSceneDialogueRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AvatarSceneDialogueService {

    private static final Pattern SPEAKER_PREFIX = Pattern.compile(
            "^\\s*([\\p{L}][\\p{L}\\p{N} _-]{0,60}):\\s*(.+)$",
            Pattern.DOTALL
    );
    private static final Set<String> VOICEOVER_SPEAKER_LABELS = Set.of(
            "creator", "founder", "narrator", "host", "speaker", "voiceover", "voice over",
            "vo", "presenter", "therapist", "coach", "expert"
    );
    private static final Set<String> DIALOGUE_CONTAINERS = Set.of(
            "lines", "dialogue", "dialogues", "spokenlines", "spoken_lines", "exchanges", "speakers"
    );

    private final CreatorAvatarSceneDialogueRepository dialogueRepository;
    private final CreatorScriptShotRepository scriptShotRepository;
    private final ObjectMapper objectMapper;

    public AvatarSceneDialogueService(
            CreatorAvatarSceneDialogueRepository dialogueRepository,
            CreatorScriptShotRepository scriptShotRepository,
            ObjectMapper objectMapper
    ) {
        this.dialogueRepository = dialogueRepository;
        this.scriptShotRepository = scriptShotRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<Integer, CreatorAvatarSceneDialogue> synchronizeSources(CreatorScript script, UUID videoRunId) {
        if (script == null || script.getId() == null || videoRunId == null) {
            return Map.of();
        }
        Map<Integer, CreatorAvatarSceneDialogue> synchronizedRows = new LinkedHashMap<>();
        for (DialogueDraft draft : extractSourceDialogues(script)) {
            CreatorAvatarSceneDialogue current = dialogueRepository
                    .findFirstByTenantIdAndUserIdAndVideoRunIdAndSceneNumberAndSourceTrueAndCurrentTrue(
                            script.getTenantId(),
                            script.getUserId(),
                            videoRunId,
                            draft.sceneNumber()
                    )
                    .orElse(null);
            if (current != null && current.getSourceFingerprint().equals(draft.sourceFingerprint())) {
                current.setDialogueText(draft.dialogueText());
                current.setSpeaker(blankToNull(draft.speaker()));
                current.setDialoguePayload(copyMap(draft.dialoguePayload()));
                current.setScreenplayContext(copyMap(draft.screenplayContext()));
                current.setScriptShotId(draft.scriptShotId());
                current.setSourceKind(draft.sourceKind());
                current.setSourcePath(draft.sourcePath());
                current.setUpdatedAt(OffsetDateTime.now());
                synchronizedRows.put(draft.sceneNumber(), dialogueRepository.save(current));
                continue;
            }
            if (current != null) {
                dialogueRepository.retireLineage(current.getRootDialogueId(), OffsetDateTime.now());
            }
            UUID dialogueId = UUID.randomUUID();
            Integer previousVersion = dialogueRepository.findMaxVersionNumber(videoRunId, draft.sceneNumber());
            CreatorAvatarSceneDialogue source = CreatorAvatarSceneDialogue.builder()
                    .id(dialogueId)
                    .rootDialogueId(dialogueId)
                    .tenantId(script.getTenantId())
                    .userId(script.getUserId())
                    .projectId(script.getProjectId())
                    .videoRunId(videoRunId)
                    .scriptId(script.getId())
                    .scriptShotId(draft.scriptShotId())
                    .sceneNumber(draft.sceneNumber())
                    .shotNumber(draft.shotNumber())
                    .sequenceNumber(draft.sequenceNumber())
                    .speaker(blankToNull(draft.speaker()))
                    .dialogueRole("spoken_dialogue")
                    .language(draft.language())
                    .languageKey(languageKey(draft.language()))
                    .languageCode(languageCode(draft.language()))
                    .dialogueText(draft.dialogueText())
                    .sourceKind(draft.sourceKind())
                    .sourcePath(draft.sourcePath())
                    .sourceFingerprint(draft.sourceFingerprint())
                    .dialoguePayload(copyMap(draft.dialoguePayload()))
                    .screenplayContext(copyMap(draft.screenplayContext()))
                    .translationMetadata(new LinkedHashMap<>())
                    .source(true)
                    .current(true)
                    .versionNumber(previousVersion == null ? 1 : previousVersion + 1)
                    .build();
            synchronizedRows.put(draft.sceneNumber(), dialogueRepository.save(source));
        }
        return synchronizedRows;
    }

    @Transactional(readOnly = true)
    public Map<Integer, CreatorAvatarSceneDialogue> currentSources(
            String tenantId,
            String userId,
            UUID videoRunId
    ) {
        if (videoRunId == null) {
            return Map.of();
        }
        Map<Integer, CreatorAvatarSceneDialogue> rows = new LinkedHashMap<>();
        dialogueRepository
                .findByTenantIdAndUserIdAndVideoRunIdAndCurrentTrueOrderBySceneNumberAscSequenceNumberAsc(
                        tenantId,
                        userId,
                        videoRunId
                )
                .stream()
                .filter(row -> Boolean.TRUE.equals(row.getSource()))
                .forEach(row -> rows.putIfAbsent(row.getSceneNumber(), row));
        return rows;
    }

    @Transactional(readOnly = true)
    public Optional<CreatorAvatarSceneDialogue> currentTranslation(
            UUID rootDialogueId,
            String language
    ) {
        if (rootDialogueId == null || language == null || language.isBlank()) {
            return Optional.empty();
        }
        return dialogueRepository.findFirstByRootDialogueIdAndLanguageKeyAndCurrentTrue(
                rootDialogueId,
                languageKey(language)
        );
    }

    @Transactional(readOnly = true)
    public List<CreatorAvatarSceneDialogue> currentVariants(UUID rootDialogueId) {
        if (rootDialogueId == null) {
            return List.of();
        }
        return dialogueRepository.findByRootDialogueIdAndCurrentTrueOrderByCreatedAtAsc(rootDialogueId)
                .stream()
                .sorted((left, right) -> {
                    if (Boolean.TRUE.equals(left.getSource()) == Boolean.TRUE.equals(right.getSource())) {
                        return left.getCreatedAt() == null || right.getCreatedAt() == null
                                ? 0
                                : left.getCreatedAt().compareTo(right.getCreatedAt());
                    }
                    return Boolean.TRUE.equals(left.getSource()) ? -1 : 1;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<CreatorAvatarSceneDialogue> resolveCurrentVariant(
            UUID rootDialogueId,
            UUID dialogueId,
            String language
    ) {
        if (rootDialogueId == null) {
            return Optional.empty();
        }
        String requestedLanguageKey = language == null || language.isBlank() ? "" : languageKey(language);
        if (dialogueId != null) {
            return dialogueRepository.findById(dialogueId)
                    .filter(row -> rootDialogueId.equals(row.getRootDialogueId()))
                    .filter(row -> Boolean.TRUE.equals(row.getCurrent()))
                    .filter(row -> requestedLanguageKey.isBlank() || requestedLanguageKey.equals(row.getLanguageKey()));
        }
        if (requestedLanguageKey.isBlank()) {
            return Optional.empty();
        }
        return dialogueRepository.findFirstByRootDialogueIdAndLanguageKeyAndCurrentTrue(
                rootDialogueId,
                requestedLanguageKey
        );
    }

    @Transactional
    public CreatorAvatarSceneDialogue saveTranslation(
            CreatorAvatarSceneDialogue source,
            String targetLanguage,
            String targetLanguageCode,
            String translatedText,
            UUID promptRunId,
            UUID jobId,
            String provider,
            String model
    ) {
        if (source == null || source.getRootDialogueId() == null) {
            throw new IllegalArgumentException("Avatar source dialogue is required.");
        }
        String normalizedText = normalizeText(translatedText);
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("Translated avatar dialogue is required.");
        }
        String targetKey = languageKey(targetLanguage);
        if (targetKey.equals(source.getLanguageKey())) {
            return source;
        }
        String fingerprint = fingerprint(targetKey + "\n" + normalizedText);
        CreatorAvatarSceneDialogue current = dialogueRepository
                .findFirstByRootDialogueIdAndLanguageKeyAndCurrentTrue(source.getRootDialogueId(), targetKey)
                .orElse(null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceDialogueId", source.getId().toString());
        metadata.put("rootDialogueId", source.getRootDialogueId().toString());
        metadata.put("sourceLanguage", source.getLanguage());
        metadata.put("targetLanguage", targetLanguage);
        metadata.put("targetLanguageCode", firstNonBlank(targetLanguageCode, languageCode(targetLanguage)));
        metadata.put("jobId", jobId == null ? null : jobId.toString());
        metadata.put("promptRunId", promptRunId == null ? null : promptRunId.toString());
        metadata.put("provider", provider);
        metadata.put("model", model);
        if (current != null && fingerprint.equals(current.getSourceFingerprint())) {
            current.setTranslationMetadata(metadata);
            current.setTranslationPromptRunId(promptRunId);
            current.setTranslationProvider(blankToNull(provider));
            current.setTranslationModel(blankToNull(model));
            current.setUpdatedAt(OffsetDateTime.now());
            return dialogueRepository.save(current);
        }
        if (current != null) {
            dialogueRepository.retireLanguageVariant(source.getRootDialogueId(), targetKey, OffsetDateTime.now());
        }
        Integer previousVersion = dialogueRepository.findMaxVersionNumber(
                source.getVideoRunId(),
                source.getSceneNumber()
        );
        Map<String, Object> dialoguePayload = copyMap(source.getDialoguePayload());
        dialoguePayload.put("translatedDialogue", normalizedText);
        return dialogueRepository.save(CreatorAvatarSceneDialogue.builder()
                .id(UUID.randomUUID())
                .rootDialogueId(source.getRootDialogueId())
                .tenantId(source.getTenantId())
                .userId(source.getUserId())
                .projectId(source.getProjectId())
                .videoRunId(source.getVideoRunId())
                .scriptId(source.getScriptId())
                .scriptShotId(source.getScriptShotId())
                .sceneNumber(source.getSceneNumber())
                .shotNumber(source.getShotNumber())
                .sequenceNumber(source.getSequenceNumber())
                .speaker(source.getSpeaker())
                .dialogueRole(source.getDialogueRole())
                .language(targetLanguage)
                .languageKey(targetKey)
                .languageCode(firstNonBlank(targetLanguageCode, languageCode(targetLanguage)))
                .dialogueText(normalizedText)
                .sourceKind("ai_translation")
                .sourcePath(source.getSourcePath())
                .sourceFingerprint(fingerprint)
                .dialoguePayload(dialoguePayload)
                .screenplayContext(copyMap(source.getScreenplayContext()))
                .translationMetadata(metadata)
                .translationProvider(blankToNull(provider))
                .translationModel(blankToNull(model))
                .translationPromptRunId(promptRunId)
                .source(false)
                .current(true)
                .versionNumber(previousVersion == null ? 1 : previousVersion + 1)
                .build());
    }

    public List<DialogueDraft> extractSourceDialogues(CreatorScript script) {
        if (script == null) {
            return List.of();
        }
        Map<String, Object> screenplayPayload = copyMap(script.getScriptPayload());
        List<Map<String, Object>> rawShots = mapList(screenplayPayload.get("shots"));
        if (rawShots.isEmpty()) {
            rawShots = script.getShots() == null ? List.of() : script.getShots();
            screenplayPayload.put("shots", rawShots);
        }
        AvatarScreenplayDocument screenplay = objectMapper.convertValue(
                screenplayPayload,
                AvatarScreenplayDocument.class
        );
        Map<Integer, CreatorScriptShot> persistedShots = new LinkedHashMap<>();
        if (script.getId() != null && scriptShotRepository != null) {
            scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId())
                    .forEach(shot -> persistedShots.putIfAbsent(positive(shot.getShotNumber(), 1), shot));
        }

        List<DialogueDraft> drafts = new ArrayList<>();
        List<AvatarScreenplayDocument.Shot> typedShots = screenplay.getShots();
        for (int index = 0; index < typedShots.size(); index++) {
            AvatarScreenplayDocument.Shot shot = typedShots.get(index);
            int shotNumber = positive(shot.getShotNumber(), index + 1);
            int sceneNumber = positive(shot.getSceneNumber(), shotNumber);
            int sequenceNumber = positive(shot.getSequenceNumber(), index + 1);
            Map<String, Object> rawShot = index < rawShots.size()
                    ? copyMap(rawShots.get(index))
                    : objectMapper.convertValue(shot, new TypeReference<LinkedHashMap<String, Object>>() {});

            ExtractedText extracted = extractStructuredDialogue(shot.getDialogue(), index);
            if (extracted.text().isBlank()) {
                String voiceOver = firstNonBlank(shot.getVoiceOver(), shot.getVoiceover(), shot.getNarration());
                if (!voiceOver.isBlank()) {
                    SpeakerText speakerText = stripSpeakerPrefix(voiceOver);
                    extracted = new ExtractedText(
                            speakerText.text(),
                            speakerText.speaker(),
                            "voice_over",
                            "shots[" + index + "].voiceOver",
                            List.of(Map.of(
                                    "speaker", speakerText.speaker(),
                                    "line", speakerText.text()
                            ))
                    );
                }
            }
            if (extracted.text().isBlank()) {
                extracted = extractSrtDialogue(shot.getSrtCues(), index);
            }
            if (extracted.text().isBlank()) {
                continue;
            }

            Map<String, Object> dialoguePayload = new LinkedHashMap<>();
            dialoguePayload.put("structuredDialogue", jsonValue(shot.getDialogue()));
            dialoguePayload.put("extractedLines", extracted.lines());
            dialoguePayload.put("speaker", extracted.speaker());
            dialoguePayload.put("sourceKind", extracted.sourceKind());
            dialoguePayload.put("sourcePath", extracted.sourcePath());

            Map<String, Object> screenplayContext = copyMap(screenplayPayload);
            screenplayContext.remove("shots");
            screenplayContext.put("shot", rawShot);
            screenplayContext.put("hook", screenplay.getHook());
            screenplayContext.put("hooks", screenplay.getHooks());
            screenplayContext.put("retentionPlan", screenplay.getRetentionPlan());
            screenplayContext.put("storytellingPlan", screenplay.getStorytellingPlan());
            screenplayContext.put("narrativeArc", screenplay.getNarrativeArc());

            CreatorScriptShot persistedShot = persistedShots.get(shotNumber);
            String sourceLanguage = firstNonBlank(
                    script.getDialogueLanguage(),
                    screenplay.getDialogueLanguage(),
                    stringValue(screenplayPayload.get("dialogueLanguage")),
                    "Hinglish"
            );
            String fingerprint = fingerprint(
                    languageKey(sourceLanguage) + "\n" + normalizeText(extracted.text()) + "\n" + jsonString(dialoguePayload)
            );
            drafts.add(new DialogueDraft(
                    sceneNumber,
                    shotNumber,
                    sequenceNumber,
                    persistedShot == null ? null : persistedShot.getId(),
                    sourceLanguage,
                    normalizeText(extracted.text()),
                    extracted.speaker(),
                    extracted.sourceKind(),
                    extracted.sourcePath(),
                    fingerprint,
                    dialoguePayload,
                    screenplayContext
            ));
        }
        return drafts;
    }

    public String languageKey(String language) {
        String value = firstNonBlank(language, "English").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "english" -> "english";
            case "english (india)", "english india", "indian english", "english indian accent" -> "english_india";
            case "hinglish" -> "hinglish";
            case "hindi" -> "hindi";
            case "bengali", "bangla" -> "bengali";
            case "tamil" -> "tamil";
            case "telugu" -> "telugu";
            case "marathi" -> "marathi";
            case "gujarati" -> "gujarati";
            case "kannada" -> "kannada";
            case "malayalam" -> "malayalam";
            case "punjabi" -> "punjabi";
            default -> value.replace(' ', '_');
        };
    }

    public String languageCode(String language) {
        return switch (languageKey(language)) {
            case "hinglish", "hindi" -> "hi-IN";
            case "english_india" -> "en-IN";
            case "bengali" -> "bn-IN";
            case "tamil" -> "ta-IN";
            case "telugu" -> "te-IN";
            case "marathi" -> "mr-IN";
            case "gujarati" -> "gu-IN";
            case "kannada" -> "kn-IN";
            case "malayalam" -> "ml-IN";
            case "punjabi" -> "pa-IN";
            case "english" -> "en-IN";
            case "spanish" -> "es-ES";
            case "french" -> "fr-FR";
            case "german" -> "de-DE";
            case "portuguese" -> "pt-BR";
            case "italian" -> "it-IT";
            case "arabic" -> "ar-SA";
            case "japanese" -> "ja-JP";
            case "korean" -> "ko-KR";
            case "chinese" -> "zh-CN";
            case "indonesian" -> "id-ID";
            case "vietnamese" -> "vi-VN";
            case "thai" -> "th-TH";
            case "russian" -> "ru-RU";
            case "turkish" -> "tr-TR";
            case "dutch" -> "nl-NL";
            case "polish" -> "pl-PL";
            default -> "und";
        };
    }

    private ExtractedText extractStructuredDialogue(JsonNode dialogue, int shotIndex) {
        if (dialogue == null || dialogue.isNull() || dialogue.isMissingNode()) {
            return ExtractedText.empty();
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        collectDialogueLines(dialogue, "", "shots[" + shotIndex + "].dialogue", lines);
        if (lines.isEmpty()) {
            return ExtractedText.empty();
        }
        String text = lines.stream()
                .map(line -> stringValue(line.get("line")))
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        LinkedHashSet<String> speakers = new LinkedHashSet<>();
        lines.stream()
                .map(line -> stringValue(line.get("speaker")))
                .filter(speaker -> !speaker.isBlank())
                .forEach(speakers::add);
        String speaker = String.join(", ", speakers);
        return new ExtractedText(
                normalizeText(text),
                speaker,
                "structured_dialogue",
                "shots[" + shotIndex + "].dialogue",
                lines
        );
    }

    private void collectDialogueLines(
            JsonNode node,
            String inheritedSpeaker,
            String path,
            List<Map<String, Object>> lines
    ) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = normalizeText(node.asText());
            if (!text.isBlank()) {
                SpeakerText parsed = stripSpeakerPrefix(text);
                lines.add(linePayload(firstNonBlank(inheritedSpeaker, parsed.speaker()), parsed.text(), path, Map.of()));
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode item = node.get(index);
                String text = directLine(item);
                if (!text.isBlank()) {
                    SpeakerText parsed = stripSpeakerPrefix(text);
                    String speaker = firstNonBlank(textValue(item, "speaker"), inheritedSpeaker, parsed.speaker());
                    lines.add(linePayload(speaker, parsed.text(), path + "[" + index + "]", jsonMap(item)));
                } else if (item != null && (item.isArray() || item.isTextual())) {
                    collectDialogueLines(item, inheritedSpeaker, path + "[" + index + "]", lines);
                }
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String direct = directLine(node);
        if (!direct.isBlank()) {
            SpeakerText parsed = stripSpeakerPrefix(direct);
            lines.add(linePayload(
                    firstNonBlank(textValue(node, "speaker"), inheritedSpeaker, parsed.speaker()),
                    parsed.text(),
                    path,
                    jsonMap(node)
            ));
            return;
        }
        node.fields().forEachRemaining(field -> {
            String fieldName = field.getKey();
            JsonNode value = field.getValue();
            if (value != null && value.isArray()) {
                collectDialogueLines(value, fieldName, path + "." + fieldName, lines);
            } else if (DIALOGUE_CONTAINERS.contains(fieldName.toLowerCase(Locale.ROOT))) {
                collectDialogueLines(value, inheritedSpeaker, path + "." + fieldName, lines);
            }
        });
    }

    private ExtractedText extractSrtDialogue(JsonNode srtCues, int shotIndex) {
        if (srtCues == null || !srtCues.isArray()) {
            return ExtractedText.empty();
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int index = 0; index < srtCues.size(); index++) {
            JsonNode cue = srtCues.get(index);
            String text = firstNonBlank(textValue(cue, "text"), textValue(cue, "caption"));
            if (!text.isBlank()) {
                lines.add(linePayload("", text, "shots[" + shotIndex + "].srtCues[" + index + "]", jsonMap(cue)));
            }
        }
        String text = lines.stream()
                .map(line -> stringValue(line.get("line")))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return text.isBlank()
                ? ExtractedText.empty()
                : new ExtractedText(
                        normalizeText(text),
                        "",
                        "srt_cues",
                        "shots[" + shotIndex + "].srtCues",
                        lines
                );
    }

    private Map<String, Object> linePayload(
            String speaker,
            String text,
            String path,
            Map<String, Object> details
    ) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("speaker", firstNonBlank(speaker, ""));
        line.put("line", normalizeText(text));
        line.put("sourcePath", path);
        line.put("details", details);
        return line;
    }

    private String directLine(JsonNode node) {
        return firstNonBlank(textValue(node, "line"), textValue(node, "text"));
    }

    private String textValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() ? normalizeText(value.asText()) : "";
    }

    private SpeakerText stripSpeakerPrefix(String value) {
        String normalized = normalizeText(value);
        Matcher matcher = SPEAKER_PREFIX.matcher(normalized);
        if (!matcher.matches()) {
            return new SpeakerText("", normalized);
        }
        String candidate = normalizeText(matcher.group(1));
        if (!VOICEOVER_SPEAKER_LABELS.contains(candidate.toLowerCase(Locale.ROOT))) {
            return new SpeakerText("", normalized);
        }
        return new SpeakerText(candidate, normalizeText(matcher.group(2)));
    }

    private Map<String, Object> jsonMap(JsonNode value) {
        if (value == null || value.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(value, Object.class);
    }

    private String jsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not fingerprint avatar dialogue.", ex);
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                rows.add(objectMapper.convertValue(item, new TypeReference<LinkedHashMap<String, Object>>() {}));
            }
        }
        return rows;
    }

    public record DialogueDraft(
            int sceneNumber,
            int shotNumber,
            int sequenceNumber,
            UUID scriptShotId,
            String language,
            String dialogueText,
            String speaker,
            String sourceKind,
            String sourcePath,
            String sourceFingerprint,
            Map<String, Object> dialoguePayload,
            Map<String, Object> screenplayContext
    ) {
    }

    private record ExtractedText(
            String text,
            String speaker,
            String sourceKind,
            String sourcePath,
            List<Map<String, Object>> lines
    ) {
        private static ExtractedText empty() {
            return new ExtractedText("", "", "", "", List.of());
        }
    }

    private record SpeakerText(String speaker, String text) {
    }
}
