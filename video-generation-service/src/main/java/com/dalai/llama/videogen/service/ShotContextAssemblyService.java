package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.AnchorType;
import com.dalai.llama.videogen.domain.AspectRatio;
import com.dalai.llama.videogen.domain.MoodProfile;
import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.ShotSize;
import com.dalai.llama.videogen.domain.TimeOfDay;
import com.dalai.llama.videogen.domain.VideoResolution;
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
import com.dalai.llama.videogen.dto.shotcontext.ReferenceFrame;
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
 * <p>Self-contained: everything needed comes from the prepare bundle, including the project's
 * continuity locks. Deterministic, no LLM in this step; the LLM only enters at prompt compression
 * downstream.
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
        // No ProjectScenePreparation gate here any more. It used to 409 unless Stage 1 (POST
        // /projects/{id}/prepare) had run, but the only thing this method took from that row was
        // the continuity template -- and it never actually read it, since buildContinuityAnchors
        // returned an empty list. The locks now come from the bundle directly, so requiring
        // Stage 1 first was gating a real prepare on a row that contributed nothing.
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
                buildNarrative(shot, bundle.script()),
                buildCharacters(scopeToShot(shot, castAssignments), profilesById, buildPerformanceDirection(shot)),
                buildEnvironment(shot),
                buildLighting(shot, shotBundle.lightingPlan(), lightingImage),
                buildCamera(shot, shotBundle.cameraPlan(), cameraPlanImage),
                buildProductBrand(shotBundle.productReference()),
                buildTechnical(shot, overrides,
                        bundle.projectConfig() == null ? null : bundle.projectConfig().preferredVideoModel(),
                        bundle.projectConfig() == null ? null : bundle.projectConfig().preferredTtsModel()),
                buildContinuityAnchors(bundle.continuityBible()),
                buildAudioAmbience(shot, shotBundle.backgroundMusic()),
                buildDialogueBeats(beats, castAssignments, profilesById, bundle.script(), shot.emotion(),
                        bundle.projectConfig() == null ? null : bundle.projectConfig().dialogueLanguage()),
                buildReferenceFrames(shotImages)
        );

        FeatureFlags flagsOverride = overrides == null ? null : overrides.featureFlagOverrides();
        ShotPromptSources sources = new ShotPromptSources(
                shot.id(),
                shotBundle.cameraPlan() == null ? null : shotBundle.cameraPlan().id(),
                shotBundle.lightingPlan() == null ? null : shotBundle.lightingPlan().id(),
                shotBundle.productReference() == null ? null : shotBundle.productReference().id(),
                shotBundle.backgroundMusic() == null ? null : shotBundle.backgroundMusic().id(),
                OffsetDateTime.now(),
                shotBundle.foleyCues() == null ? List.of() : shotBundle.foleyCues()
        );
        return new AssembledShot(shotContext, flagsOverride, overrides == null ? null : overrides.modelPin(), sources);
    }

    /** {@code screenplaySlug} carries the project's story frame -- hook, beat plan, arc, logline.
     * Every shot in a project shares it, which is the point: a shot generated with no idea what
     * the film is doing around it is why shots stop feeling like one piece. It rides in the slug
     * field because that is the only free-text slot Narrative has; arcPosition stays null since
     * pre-production's bundle carries no per-shot screenplay scene to compute it from. */
    private Narrative buildNarrative(PreProductionViews.ShotView shot, PreProductionViews.ScriptView script) {
        String storyFrame = buildStoryFrame(script);
        String dialogue = dialogueFor(shot);
        // What happens in frame. Prefer the shot's action; scriptLine stands in when a shot has no
        // separate action written, which is common for dialogue shots where the line IS the beat.
        String action = hasText(shot.action()) ? shot.action() : shot.scriptLine();
        if (!hasText(action) && !hasText(dialogue) && storyFrame == null) {
            return null;
        }
        return new Narrative(action, storyFrame, null, dialogue);
    }

    /** The spoken line. voiceOver is the field the creator edits to change what is said, so it
     * wins; scriptLine counts as dialogue only for DIALOGUE shots, where it is the spoken line
     * rather than scene direction. Same rule the video workspace applies when deciding whether a
     * shot has anything to dub, so what the creator sees on the card and what reaches the prompt
     * agree. */
    private String dialogueFor(PreProductionViews.ShotView shot) {
        if (hasText(shot.voiceOver())) {
            return shot.voiceOver();
        }
        return "DIALOGUE".equals(shot.shotType()) && hasText(shot.scriptLine()) ? shot.scriptLine() : null;
    }

    private String buildStoryFrame(PreProductionViews.ScriptView script) {
        if (script == null) {
            return null;
        }
        return joinNonBlank(" | ",
                labelled("Logline", script.logline()),
                labelled("Hook", script.hook()),
                labelled("Hook strategy", script.hookStrategy()),
                labelled("Beat plan", script.beatPlan()),
                labelled("Emotional arc", script.emotionalArc()),
                labelled("Central conflict", script.centralConflict()),
                labelled("Ending payoff", script.endingPayoff()),
                labelled("Pacing", script.pacingStyle()),
                labelled("Storytelling type", script.storytellingType()),
                labelled("Setting", script.setting()));
    }

    /** Narrows the project's cast to the people actually in THIS shot.
     *
     * <p>castAssignments is project-scope -- every character in the project -- so using it whole
     * put every character on every shot, and every one of their face crops became a reference
     * image on every shot. A two-hander in a five-character project was conditioned on five
     * faces, and a B-roll shot of a product on all five as well.
     *
     * <p>pre-production already resolves who is in a shot (shot.cast, from primaryCharacterKey
     * through ScriptCharacter to the cast assignment), so this matches on that. A shot with no
     * resolved cast -- no primary character, or a NARRATOR, who is never in frame -- gets no
     * characters, which is the correct answer for B-roll, product and motion-graphic shots.
     * Falls back to the full list only when the bundle carries no cast at all, so a shot planned
     * before pre-production populated it behaves as it did before rather than losing its cast. */
    private List<PreProductionViews.CastAssignmentView> scopeToShot(
            PreProductionViews.ShotView shot,
            List<PreProductionViews.CastAssignmentView> castAssignments) {
        if (shot.cast() == null) {
            return hasText(shot.primaryCharacterKey()) ? List.of() : castAssignments;
        }
        UUID castProfileId = shot.cast().castProfileId();
        if (castProfileId == null) {
            return List.of();
        }
        return castAssignments.stream()
                .filter(assignment -> castProfileId.equals(assignment.castProfileId()))
                .toList();
    }

    /** {@code performance} is this shot's own expression/body-language/emotion direction, folded
     * into each character's performanceDirection -- those columns describe how the people in THIS
     * shot should act, so they belong on the character, not in a camera or editing note. */
    private List<Character> buildCharacters(
            List<PreProductionViews.CastAssignmentView> castAssignments,
            Map<UUID, PreProductionViews.CastProfileView> profilesById,
            String performance) {
        List<Character> result = new ArrayList<>();
        for (PreProductionViews.CastAssignmentView assignment : castAssignments) {
            PreProductionViews.CastProfileView profile = profilesById.get(assignment.castProfileId());
            result.add(new Character(
                    assignment.castProfileId() == null ? null : assignment.castProfileId().toString(),
                    profile == null ? null : profile.faceRefBucket(),
                    profile == null ? null : profile.faceRefObjectKey(),
                    assignment.wardrobeNote(),
                    joinNonBlank(" | ", assignment.performanceDirection(), performance),
                    profile == null ? null : profile.voiceRefBucket(),
                    profile == null ? null : profile.voiceRefObjectKey()
            ));
        }
        return result;
    }

    /** The shot's performance direction, from the columns that describe how it should be played. */
    private String buildPerformanceDirection(PreProductionViews.ShotView shot) {
        return joinNonBlank(" | ",
                labelled("Expression", shot.expression()),
                labelled("Body language", shot.bodyLanguage()),
                labelled("Emotion", shot.emotion()));
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
        // cameraNote is the catch-all for the shot's own free-text camera direction. Angle,
        // movement, lens and composition live as their own columns and had nowhere to go before
        // the Camera record was widened; they fold in here rather than becoming five more
        // top-level fields the strategies would each have to know about individually.
        String note = joinNonBlank(" | ",
                shot.cameraNote(),
                labelled("Angle", shot.cameraAngle()),
                labelled("Movement", shot.cameraMovement()),
                labelled("Lens", shot.lensSuggestion()),
                labelled("Composition", shot.composition()),
                labelled("Screen direction", shot.screenDirection()),
                labelled("Coverage", shot.coverageType()),
                plan == null ? null : plan.blockingMap());
        PreProductionViews.CinematographyView cine = shot.cinematography();
        if (shotSize == null && note == null && image == null && cine == null) {
            return null;
        }
        if (cine == null) {
            return new Camera(shotSize, note,
                    image == null ? null : image.bucket(),
                    image == null ? null : image.objectKey());
        }
        return new Camera(
                shotSize, note,
                image == null ? null : image.bucket(),
                image == null ? null : image.objectKey(),
                cine.cameraBody(), cine.sensor(), cine.captureFormat(), cine.recordingCharacteristics(),
                cine.positionHeight(), cine.positionDistance(), cine.positionLateral(),
                cine.positionElevation(), cine.positionOrientation(),
                cine.lensFocalLength(), cine.lensType(), cine.lensOpticalFormat(),
                cine.lensDistortion(), cine.lensCompression(), cine.lensCharacter(),
                cine.framing(), cine.subjectPlacement(), cine.headroom(), cine.leadRoom(), cine.visualBalance(),
                cine.focusTarget(), cine.focusDistance(), cine.depthOfField(), cine.rackFocus(), cine.focusBehaviour(),
                cine.movementType(), cine.movementTrajectory(), cine.movementSpeed(),
                cine.movementAcceleration(), cine.movementRotation(), cine.movementSubjectRelationship(),
                cine.support(),
                cine.aperture(), cine.iso(), cine.shutter(), cine.ndFilter(), cine.dynamicRange(),
                cine.shutterAngle(), cine.motionBlur(), cine.slowMotion(),
                cine.filtrationDiffusion(), cine.filtrationNd(), cine.filtrationPolarizer(), cine.filtrationSpecialty(),
                cine.contrast(), cine.colorResponse(), cine.grain(), cine.halation(),
                cine.bloom(), cine.sharpness(), cine.flare()
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
        // The shot's direction and delivery-format notes. These are free-text instructions with no
        // structured home on ShotContext, so they ride along with the editing notes rather than
        // each gaining a top-level field every prompt strategy would have to learn.
        //
        // Everything the shot plan says is included. Nothing is held back here on the grounds that
        // it might not fit -- PROMPT_COMPRESSION rewrites to length and is instructed to drop
        // workflow notes first if it ever truly has to, so the decision about what survives is
        // made once, with the whole prompt in view, instead of being pre-empted per field here.
        //
        // The one real exclusion is sketchPrompt: it is the instruction for generating the
        // storyboard SKETCH, not a description of this video. Including it would tell the model to
        // draw a storyboard frame.
        String editingNotes = joinNonBlank(" | ",
                shot.editingNotes(),
                labelled("Direction", shot.creatorDirection()),
                labelled("Director note", shot.directorNote()),
                labelled("Cinematic execution", shot.cinematicExecution()),
                labelled("Retention goal", shot.retentionGoal()),
                labelled("Mobile focus area", shot.mobileFocusArea()),
                labelled("Safe zone", shot.safeZoneNotes()),
                labelled("On-screen text", shot.textOverlay()),
                labelled("Subtitle position", shot.subtitlePosition()),
                labelled("Cultural references", shot.culturalReferences()),
                labelled("Product shot type", shot.productShotType()),
                labelled("Execution difficulty", shot.executionDifficulty()),
                labelled("People in frame", shot.peopleInFrame() == null ? null : String.valueOf(shot.peopleInFrame())),
                overrides == null ? null : overrides.customNotes());
        // Precedence: per-request override (user picked a model on THIS prepare) beats the
        // project-wide dropdown pin (creator picked a default for the project) beats auto-resolve
        // (the strategy resolver's own default). Feeds ProviderPromptStrategyResolver.resolve(modelId).
        String pinnedModel = overrides != null && overrides.modelPin() != null
                ? overrides.modelPin()
                : (projectPreferredModel != null && !projectPreferredModel.isBlank() ? projectPreferredModel : null);
        VideoResolution resolution = overrides == null ? null : VideoResolution.fromWireValue(overrides.resolutionOverride());
        String ttsModel = projectPreferredTtsModel != null && !projectPreferredTtsModel.isBlank() ? projectPreferredTtsModel : null;
        return new Technical(duration, aspectRatio, resolution, null, pinnedModel, null, editingNotes,
                ttsModel, shot.fps());
    }

    /** The project's continuity locks, as anchors on every shot's context. llm-gateway's prompt
     * strategies already render these ("Continuity: ..." in the default/Seedance line shape, a
     * clause in Wan's) -- this is the step that was missing, so until now the locks reached no
     * prompt at all and each shot was composed as if the project had no continuity rules.
     *
     * <p>Read from the prepare bundle, which both the single-shot and the batch path already
     * fetch, so wardrobe/set/camera/lighting locks cost no extra call.
     *
     * <p>Category maps to the anchor type by scope: an identity or wardrobe lock holds across the
     * whole campaign, a set/camera/lighting lock is scene-local, and a prop lock is its own type.
     * The category name is kept in the description because that's the only part the model
     * actually reads -- "WARDROBE_APPEARANCE: Maya: red kurta" tells it what kind of constraint
     * it is, where the bare value wouldn't. */
    private List<ContinuityAnchor> buildContinuityAnchors(PreProductionViews.ContinuityBibleView bible) {
        if (bible == null || bible.locks() == null) {
            return List.of();
        }
        return bible.locks().stream()
                .filter(lock -> hasText(lock.value()))
                .map(lock -> new ContinuityAnchor(
                        anchorTypeFor(lock.category()),
                        null,
                        hasText(lock.category()) ? lock.category() + ": " + lock.value() : lock.value(),
                        null))
                .toList();
    }

    private AnchorType anchorTypeFor(String category) {
        if (category == null) {
            return AnchorType.LOCAL_SCENE;
        }
        return switch (category) {
            case "CHARACTER_IDENTITY", "WARDROBE_APPEARANCE" -> AnchorType.GLOBAL_CAMPAIGN;
            case "SET_PROP" -> AnchorType.PROP;
            default -> AnchorType.LOCAL_SCENE;
        };
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

    /** Joins the non-blank parts, or null when nothing survives -- so a field that every source
     * left empty stays absent rather than becoming a dangling separator in the prompt. */
    private static String joinNonBlank(String separator, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (hasText(part)) {
                if (joined.length() > 0) {
                    joined.append(separator);
                }
                joined.append(part.trim());
            }
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /** "Angle: low, looking up" -- the label carries the meaning once several columns are folded
     * into one free-text field, where the bare value would just read as another clause. */
    private static String labelled(String label, String value) {
        return hasText(value) ? label + ": " + value.trim() : null;
    }

    /** The shot's own frame, as a single reference. Preference order is deliberate: an approved
     * PRODUCTION still is the closest thing to "what this shot must look like", a STORYBOARD frame
     * is the rough stand-in when no still exists, and MOTION_GRAPHIC is the equivalent for MG
     * shots (which never get PRODUCTION/STORYBOARD at all). Only one is attached -- handing an
     * image model both a polished still and its own rough sketch of the same beat pulls the
     * generation in two directions.
     *
     * <p>LIGHTING and CAMERA_PLAN are not included here: those already flow onto the ShotContext
     * as Lighting.dpLightingImage* / Camera.cameraPlanImage* and are saved by saveReferences from
     * there, so adding them again would double up the rows. */
    private List<ReferenceFrame> buildReferenceFrames(List<PreProductionViews.ShotImageView> images) {
        PreProductionViews.ShotImageView frame = pickImage(images, "PRODUCTION");
        if (frame == null) {
            frame = pickImage(images, "STORYBOARD");
        }
        if (frame == null) {
            frame = pickImage(images, "MOTION_GRAPHIC");
        }
        if (frame == null || frame.bucket() == null || frame.objectKey() == null) {
            return List.of();
        }
        return List.of(new ReferenceFrame(ReferenceKind.STORYBOARD, frame.bucket(), frame.objectKey()));
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
            OffsetDateTime bundleSnapshotAt,
            /** The shot's foley cue sheet as pre-production derived it when the shot was planned.
             * Carried here rather than on ShotContext because cues are not prompt content -- they
             * are saved against the prompt row and read at dispatch; the prompt text itself never
             * mentions them. Empty for a shot pre-production has not derived cues for yet. */
            List<PreProductionViews.ShotFoleyCueView> foleyCues
    ) {

        /** Pre-foleyCues arity, for callers that have no bundle to read cues from. */
        public ShotPromptSources(UUID shotId, UUID cameraPlanId, UUID lightingPlanId, UUID productReferenceId,
                                 UUID backgroundMusicId, OffsetDateTime bundleSnapshotAt) {
            this(shotId, cameraPlanId, lightingPlanId, productReferenceId, backgroundMusicId, bundleSnapshotAt, List.of());
        }
    }

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
