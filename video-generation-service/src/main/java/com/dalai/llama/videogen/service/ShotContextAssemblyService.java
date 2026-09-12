package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.AspectRatio;
import com.dalai.llama.videogen.domain.MoodProfile;
import com.dalai.llama.videogen.domain.ShotSize;
import com.dalai.llama.videogen.domain.TimeOfDay;
import com.dalai.llama.videogen.domain.VideoResolution;
import com.dalai.llama.videogen.domain.entity.ProjectScenePreparation;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.shotcontext.AudioAmbience;
import com.dalai.llama.videogen.dto.shotcontext.Camera;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.ContinuityAnchor;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.dto.shotcontext.Environment;
import com.dalai.llama.videogen.dto.shotcontext.Lighting;
import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import com.dalai.llama.videogen.dto.shotcontext.ProductBrand;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.dto.shotcontext.Technical;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stage 2 of the prepare-scene flow: for one shot, pull every source pre-production-service
 * produced (dialogue beats, cast + voice references, DP lighting plan + image, camera plan +
 * image, product reference, background music) and assemble a fully-populated {@link ShotContext}
 * for {@link ShotGenerationOrchestrator} to build a prompt from.
 *
 * <p>Requires {@link ScenePreparationService#prepareProject} to have run first (409 otherwise)
 * -- the project's continuity/config template is a prerequisite for a coherent per-shot prompt.
 * Deterministic, no LLM in this step; the LLM only enters at prompt compression downstream.
 *
 * <p>Missing optional sources (a shot with no camera plan yet, no cast reference, etc.)
 * degrade to null on the corresponding {@code ShotContext} field -- every field on that DTO is
 * nullable by its own class comment convention.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotContextAssemblyService {

    private final PreProductionServiceClient preProductionClient;
    private final ScenePreparationService scenePreparationService;

    /** Convenience: single-shot prepare. Fetches the fat bundle once from pre-prod, then
     * delegates to {@link #assembleFromBundle} so the same code path serves both the per-shot
     * endpoint and the batch loop (which fetches the bundle once and reuses it across N shots
     * -- the whole point of the aggregate). */
    public AssembledShot assemble(UUID tenantId, UUID projectId, UUID shotId, PrepareShotOverrides overrides) {
        PreProductionViews.PrepareBundleView bundle = preProductionClient.getPrepareBundle(tenantId, projectId)
                .orElseThrow(() -> VideoGenException.upstream("pre-production-service returned no prepare bundle for project " + projectId));
        return assembleFromBundle(tenantId, projectId, shotId, overrides, bundle);
    }

    public AssembledShot assembleFromBundle(
            UUID tenantId, UUID projectId, UUID shotId, PrepareShotOverrides overrides,
            PreProductionViews.PrepareBundleView bundle) {
        ProjectScenePreparation projectPrep = scenePreparationService.getPreparation(tenantId, projectId)
                .orElseThrow(() -> VideoGenException.conflict(
                        "Project has not been prepared yet -- call POST /v1/scenes/projects/" + projectId + "/prepare first"));

        PreProductionViews.ShotBundleView shotBundle = bundle.shots().stream()
                .filter(sb -> sb.shot() != null && shotId.equals(sb.shot().id()))
                .findFirst()
                .orElseThrow(() -> VideoGenException.notFound("No shot " + shotId + " in project " + projectId));
        PreProductionViews.ShotView shot = shotBundle.shot();

        List<PreProductionViews.CastAssignmentView> castAssignments =
                bundle.castAssignments() == null ? List.of() : bundle.castAssignments();
        List<PreProductionViews.CastProfileView> castProfiles =
                bundle.castProfiles() == null ? List.of() : bundle.castProfiles();
        List<PreProductionViews.ShotDialogueBeatView> beats =
                shotBundle.dialogueBeats() == null ? List.of() : shotBundle.dialogueBeats();
        List<PreProductionViews.ShotImageView> shotImages =
                shotBundle.shotImages() == null ? List.of() : shotBundle.shotImages();

        Map<UUID, PreProductionViews.CastProfileView> profilesById = castProfiles.stream()
                .collect(Collectors.toMap(PreProductionViews.CastProfileView::id, p -> p, (a, b) -> a));

        PreProductionViews.ShotImageView lightingImage = pickImage(shotImages, "LIGHTING");
        PreProductionViews.ShotImageView cameraPlanImage = pickImage(shotImages, "CAMERA_PLAN");

        ShotContext shotContext = new ShotContext(
                shot.shotRef(),
                buildNarrative(shot),
                buildCharacters(castAssignments, profilesById),
                buildEnvironment(shot),
                buildLighting(shot, shotBundle.lightingPlan(), lightingImage),
                buildCamera(shot, shotBundle.cameraPlan(), cameraPlanImage),
                buildProductBrand(shotBundle.productReference()),
                buildTechnical(shot, overrides,
                        bundle.projectConfig() == null ? null : bundle.projectConfig().preferredVideoModel(),
                        bundle.projectConfig() == null ? null : bundle.projectConfig().preferredTtsModel()),
                buildContinuityAnchors(projectPrep),
                buildAudioAmbience(shot, shotBundle.backgroundMusic()),
                buildDialogueBeats(beats, castAssignments, profilesById, bundle.script(), shot.emotion(),
                        bundle.projectConfig() == null ? null : bundle.projectConfig().dialogueLanguage())
        );

        FeatureFlags flagsOverride = overrides == null ? null : overrides.featureFlagOverrides();
        ShotPromptSources sources = new ShotPromptSources(
                shot.id(),
                shotBundle.cameraPlan() == null ? null : shotBundle.cameraPlan().id(),
                shotBundle.lightingPlan() == null ? null : shotBundle.lightingPlan().id(),
                shotBundle.productReference() == null ? null : shotBundle.productReference().id(),
                shotBundle.backgroundMusic() == null ? null : shotBundle.backgroundMusic().id(),
                OffsetDateTime.now()
        );
        return new AssembledShot(shotContext, flagsOverride, overrides == null ? null : overrides.modelPin(), sources);
    }

    private Narrative buildNarrative(PreProductionViews.ShotView shot) {
        if (shot.scriptLine() == null && shot.action() == null) {
            return null;
        }
        String scriptLine = shot.scriptLine() != null ? shot.scriptLine() : shot.action();
        return new Narrative(scriptLine, null, null);
    }

    private List<Character> buildCharacters(
            List<PreProductionViews.CastAssignmentView> castAssignments,
            Map<UUID, PreProductionViews.CastProfileView> profilesById) {
        List<Character> result = new ArrayList<>();
        for (PreProductionViews.CastAssignmentView assignment : castAssignments) {
            PreProductionViews.CastProfileView profile = profilesById.get(assignment.castProfileId());
            result.add(new Character(
                    assignment.castProfileId() == null ? null : assignment.castProfileId().toString(),
                    profile == null ? null : profile.faceRefBucket(),
                    profile == null ? null : profile.faceRefObjectKey(),
                    assignment.wardrobeNote(),
                    assignment.performanceDirection(),
                    profile == null ? null : profile.voiceRefBucket(),
                    profile == null ? null : profile.voiceRefObjectKey()
            ));
        }
        return result;
    }

    private Environment buildEnvironment(PreProductionViews.ShotView shot) {
        if (shot.location() == null && shot.timeOfDay() == null) {
            return null;
        }
        TimeOfDay timeOfDay = null;
        if (shot.timeOfDay() != null) {
            try {
                timeOfDay = TimeOfDay.valueOf(shot.timeOfDay());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown timeOfDay from pre-production: {}", shot.timeOfDay());
            }
        }
        return new Environment(shot.location(), timeOfDay, null, null);
    }

    private Lighting buildLighting(
            PreProductionViews.ShotView shot,
            PreProductionViews.LightingPlanView plan,
            PreProductionViews.ShotImageView image) {
        String keyLightNote = plan != null && plan.cinematicIntent() != null ? plan.cinematicIntent() : null;
        MoodProfile mood = null;
        if (shot.lightingMood() != null) {
            try {
                mood = MoodProfile.valueOf(shot.lightingMood());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown lightingMood from pre-production: {}", shot.lightingMood());
            }
        }
        if (keyLightNote == null && mood == null && image == null) {
            return null;
        }
        return new Lighting(
                keyLightNote, mood,
                image == null ? null : image.bucket(),
                image == null ? null : image.objectKey()
        );
    }

    private Camera buildCamera(
            PreProductionViews.ShotView shot,
            PreProductionViews.CameraPlanView plan,
            PreProductionViews.ShotImageView image) {
        ShotSize shotSize = null;
        if (shot.cameraShotSize() != null) {
            try {
                shotSize = ShotSize.valueOf(shot.cameraShotSize());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown cameraShotSize from pre-production: {}", shot.cameraShotSize());
            }
        }
        String note = shot.cameraNote();
        if (plan != null && plan.blockingMap() != null && !plan.blockingMap().isBlank()) {
            note = (note == null ? "" : note + " | ") + plan.blockingMap();
        }
        if (shotSize == null && note == null && image == null) {
            return null;
        }
        return new Camera(
                shotSize, note,
                image == null ? null : image.bucket(),
                image == null ? null : image.objectKey()
        );
    }

    private ProductBrand buildProductBrand(PreProductionViews.ShotProductReferenceView ref) {
        if (ref == null) {
            return null;
        }
        return new ProductBrand(
                true,
                ref.personDescription() != null ? ref.personDescription() : ref.detectedSubject(),
                ref.bucket(),
                ref.objectKey()
        );
    }

    private Technical buildTechnical(PreProductionViews.ShotView shot, PrepareShotOverrides overrides, String projectPreferredModel, String projectPreferredTtsModel) {
        Integer duration = overrides != null && overrides.durationSecondsOverride() != null
                ? overrides.durationSecondsOverride() : shot.durationSeconds();
        AspectRatio aspectRatio = null;
        if (shot.aspectRatio() != null) {
            try {
                aspectRatio = AspectRatio.valueOf(shot.aspectRatio());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown aspectRatio from pre-production: {}", shot.aspectRatio());
            }
        }
        String editingNotes = shot.editingNotes();
        if (overrides != null && overrides.customNotes() != null && !overrides.customNotes().isBlank()) {
            editingNotes = (editingNotes == null ? "" : editingNotes + " | ") + overrides.customNotes();
        }
        // Precedence: per-request override (user picked a model on THIS prepare) beats the
        // project-wide dropdown pin (creator picked a default for the project) beats auto-resolve
        // (the strategy resolver's own default). Feeds ProviderPromptStrategyResolver.resolve(modelId).
        String pinnedModel = overrides != null && overrides.modelPin() != null
                ? overrides.modelPin()
                : (projectPreferredModel != null && !projectPreferredModel.isBlank() ? projectPreferredModel : null);
        VideoResolution resolution = overrides == null ? null : VideoResolution.fromWireValue(overrides.resolutionOverride());
        String ttsModel = projectPreferredTtsModel != null && !projectPreferredTtsModel.isBlank() ? projectPreferredTtsModel : null;
        return new Technical(duration, aspectRatio, resolution, null, pinnedModel, null, editingNotes, ttsModel);
    }

    private List<ContinuityAnchor> buildContinuityAnchors(ProjectScenePreparation prep) {
        // ProjectScenePreparation stores the template text; the individual continuity locks
        // aren't broken back out here -- they're already folded into templateText which the
        // caller can consume alongside the composed prompt. Return empty rather than fabricating
        // an anchor type that the enum doesn't support.
        return List.of();
    }

    private AudioAmbience buildAudioAmbience(
            PreProductionViews.ShotView shot,
            PreProductionViews.ShotBackgroundMusicView music) {
        String ambient = shot.soundDesign();
        String mood = music != null ? music.prompt() : null;
        if (ambient == null && mood == null) {
            return null;
        }
        // Pre-prod's ShotBackgroundMusicView returns signedUrl but not bucket/objectKey today --
        // BACKGROUND_MUSIC reference-kind persistence via ShotPromptReference (which requires
        // bucket/objectKey) is deferred until pre-prod's view exposes them. Music still enters
        // the composed prompt via mood note above, so it's not lost.
        return new AudioAmbience(ambient, mood, null, null);
    }

    private List<DialogueBeat> buildDialogueBeats(
            List<PreProductionViews.ShotDialogueBeatView> beats,
            List<PreProductionViews.CastAssignmentView> castAssignments,
            Map<UUID, PreProductionViews.CastProfileView> profilesById,
            PreProductionViews.ScriptView script,
            String emotion,
            String languageCode) {
        if (beats == null || beats.isEmpty()) {
            return List.of();
        }
        // Real per-beat cast resolution: beat.characterKey (String) -> ScriptCharacter.id (UUID)
        // -> CastAssignment.castProfileId -> CastProfile.voiceRef*. Falls back to the first
        // cast profile only when a beat's speaker can't be resolved (script missing, characterKey
        // unknown, cast unassigned) -- matches the pre-prod-side assembler's own fallback.
        Map<String, UUID> scriptCharacterIdByKey = script == null || script.characters() == null
                ? Map.of()
                : script.characters().stream()
                        .filter(c -> c.characterKey() != null && c.id() != null)
                        .collect(Collectors.toMap(
                                PreProductionViews.ScriptCharacterView::characterKey,
                                PreProductionViews.ScriptCharacterView::id,
                                (a, b) -> a));
        Map<UUID, UUID> castProfileByScriptCharacterId = castAssignments.stream()
                .filter(a -> a.scriptCharacterId() != null && a.castProfileId() != null)
                .collect(Collectors.toMap(
                        PreProductionViews.CastAssignmentView::scriptCharacterId,
                        PreProductionViews.CastAssignmentView::castProfileId,
                        (a, b) -> a));
        PreProductionViews.CastProfileView primaryFallback = castAssignments.isEmpty()
                ? null : profilesById.get(castAssignments.get(0).castProfileId());
        PreProductionViews.CastProfileView fallbackProfile = primaryFallback;
        return beats.stream()
                .map(b -> {
                    PreProductionViews.CastProfileView resolved = resolveVoiceProfile(b.characterKey(), scriptCharacterIdByKey,
                            castProfileByScriptCharacterId, profilesById);
                    PreProductionViews.CastProfileView profile = resolved != null ? resolved : fallbackProfile;
                    return new DialogueBeat(b.startSeconds(), b.durationSeconds(), b.text(), b.characterKey(),
                            voiceUrlFor(profile), clonedVoiceIdFor(profile), clonedVoiceProviderIdFor(profile),
                            builtinVoiceIdFor(profile), emotion, languageCode);
                })
                .toList();
    }

    private PreProductionViews.CastProfileView resolveVoiceProfile(
            String characterKey,
            Map<String, UUID> scriptCharacterIdByKey,
            Map<UUID, UUID> castProfileByScriptCharacterId,
            Map<UUID, PreProductionViews.CastProfileView> profilesById) {
        if (characterKey == null) {
            return null;
        }
        UUID scriptCharacterId = scriptCharacterIdByKey.get(characterKey);
        if (scriptCharacterId == null) {
            return null;
        }
        UUID castProfileId = castProfileByScriptCharacterId.get(scriptCharacterId);
        if (castProfileId == null) {
            return null;
        }
        return profilesById.get(castProfileId);
    }

    /** A prepared clone takes priority over a raw uploaded sample, which takes priority over a
     * built-in voice. This mirrors pre-production-service and makes Prepare All Dialogues durable
     * through the later job snapshot. */
    private static String voiceUrlFor(PreProductionViews.CastProfileView profile) {
        if (clonedVoiceIdFor(profile) != null || profile == null
                || !hasText(profile.voiceRefBucket()) || !hasText(profile.voiceRefObjectKey())) {
            return null;
        }
        return profile.voiceRefBucket() + "/" + profile.voiceRefObjectKey();
    }

    private static String clonedVoiceIdFor(PreProductionViews.CastProfileView profile) {
        if (profile == null || !hasText(profile.clonedVoiceId()) || !hasText(profile.clonedVoiceProviderId())) {
            return null;
        }
        return profile.clonedVoiceId();
    }

    private static String clonedVoiceProviderIdFor(PreProductionViews.CastProfileView profile) {
        return clonedVoiceIdFor(profile) == null ? null : profile.clonedVoiceProviderId();
    }

    private static String builtinVoiceIdFor(PreProductionViews.CastProfileView profile) {
        if (profile == null || clonedVoiceIdFor(profile) != null || voiceUrlFor(profile) != null) {
            return null;
        }
        return profile.builtinVoiceId();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private PreProductionViews.ShotImageView pickImage(List<PreProductionViews.ShotImageView> images, String kind) {
        return images.stream()
                .filter(img -> kind.equals(img.kind()))
                .findFirst()
                .orElse(null);
    }

    /** Result of stage 2: the fully-populated {@link ShotContext} plus the UI's own feature-flag
     * override (which the orchestrator applies on top of the project's stored config) and the
     * model pin, if any. */
    public record AssembledShot(ShotContext shotContext, FeatureFlags featureFlagOverrides, String modelPin, ShotPromptSources sources) {}

    /** Source-of-truth pre-prod row ids for the shot content that fed the composed prompt.
     * Persisted on {@code ShotPrompt} so a debugger can walk back to the exact rows without
     * duplicating their content. Every field nullable; a shot without a lighting plan still
     * produces a valid prompt. See {@code shot_prompt} table's source-id columns (V20 migration). */
    public record ShotPromptSources(
            UUID shotId,
            UUID cameraPlanId,
            UUID lightingPlanId,
            UUID productReferenceId,
            UUID backgroundMusicId,
            OffsetDateTime bundleSnapshotAt
    ) {}

    /** Optional per-call overrides the UI can pass through -- everything nullable, defaults apply
     * when a field is omitted. */
    public record PrepareShotOverrides(
            FeatureFlags featureFlagOverrides,
            String modelPin,
            Integer durationSecondsOverride,
            String customNotes,
            /** Wire value (see VideoResolution) -- unlike duration/aspect-ratio this has no
             * pre-production-side default to fall back to, it's purely a generate-time cost/
             * quality choice, so a blank/unrecognized value here just means "use the provider's
             * own default" (VideoResolution.fromWireValue already returns null for that case). */
            String resolutionOverride
    ) {}
}
