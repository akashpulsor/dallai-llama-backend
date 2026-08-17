package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's startRegenerateSceneJob endpoint - validating that no
 * other shot in the run is already generating, resolving provider/model/generation-mode for the
 * requested scene, requiring billing consent when replacing an already-generated clip, and
 * queuing the async generation job. Owner-scoped (same package, owner back-reference) for the
 * same reason FounderDialoguePreparer/DialogueVoiceCloner are: this reaches through owner for
 * loadRun, loadScript, enrichScenesWithStoryboardReferences, findSceneIndex, generationModeFor,
 * providerForSceneGeneration, modelForSceneGeneration, and outputPayload - shared run/script-
 * domain primitives, not exclusive to this one flow.
 *
 * <p>Deliberately does NOT include runRegenerateSceneJob (the method that actually executes the
 * provider generation call this job starts) - that ~285-line method interleaves job-lifecycle,
 * provider generation, asset storage, wallet billing, and three different error-handling paths
 * into one orchestration, the same shape as the AudioPackGenerationService cluster scoped out of
 * Phase B for not being a separable cluster. Left in place rather than risking a blind split.
 */
final class SceneRegenerationJobStarter {

    private static final String JOB_SCREENPLAY_VIDEO_SCENE_REGENERATE = "SCREENPLAY_VIDEO_SCENE_REGENERATE";

    private final ScreenplayVideoService owner;
    private final GenerationJobService generationJobService;

    SceneRegenerationJobStarter(ScreenplayVideoService owner, GenerationJobService generationJobService) {
        this.owner = owner;
        this.generationJobService = generationJobService;
    }

    CreatorGenerationJob startRegenerateSceneJob(
            UUID runId,
            String sceneId,
            Map<String, Object> request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        ScreenplayVideoService.RunRecord record = owner.loadRun(runId, safeTenantId, safeUserId);
        Map<String, Object> run = copyMap(record.run());
        CreatorScript script = owner.loadScript(uuidValue(run.get("scriptId")), safeTenantId, safeUserId);
        List<Map<String, Object>> scenes = mapListValue(run.get("scenes"));
        scenes = owner.enrichScenesWithStoryboardReferences(script, scenes, request);
        Map<String, Object> activeScene = new VideoRunStatusEvaluator().activeSceneGeneration(scenes, sceneId);
        if (!activeScene.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Another shot is already generating. Wait for it to finish before starting the next one."
            );
        }
        Map<String, Object> inputPayload = copyMap(request);
        inputPayload.put("runId", runId.toString());
        inputPayload.put("scriptId", script.getId().toString());
        inputPayload.put("sceneId", sceneId);
        int sceneIndex = owner.findSceneIndex(scenes, sceneId);
        Map<String, Object> scene = copyMap(scenes.get(sceneIndex));
        String generationMode = owner.generationModeFor(scene, run, request);
        String provider = owner.providerForSceneGeneration(scene, run, request);
        String model = owner.modelForSceneGeneration(provider, scene, run, request);
        inputPayload.put("provider", provider);
        inputPayload.put("model", model);
        inputPayload.put("generationMode", generationMode);
        boolean replacingGeneratedClip = new VideoRunStatusEvaluator().hasClipAsset(scene);
        if (replacingGeneratedClip && !booleanValue(inputPayload.get("billingConsent"), false)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Regenerating an existing shot is a paid video-model run. Confirm the displayed provider charge and retry with billingConsent=true."
            );
        }
        String providerLabel = new VideoProviderCatalog().providerLabel(provider);
        scene.put("provider", provider);
        scene.put("targetProvider", provider);
        scene.put("model", model);
        scene.put("generationMode", generationMode);
        scene.put("billingMode", replacingGeneratedClip ? "PAID_SCENE_RERUN" : "INITIAL_OR_RESUMED_SCENE");
        scene.put("billingConsent", booleanValue(inputPayload.get("billingConsent"), false));
        scene.put("status", "VIDEO_GENERATION_QUEUED");
        scene.put("queuedAt", OffsetDateTime.now().toString());
        scene.put("message", "Shot queued for " + providerLabel + " generation.");
        scenes.set(sceneIndex, scene);
        run.put("scenes", scenes);
        run.put("sceneClips", scenes);
        run.put("provider", provider);
        run.put("model", model);
        run.put("status", "VIDEO_GENERATION_QUEUED");
        run.put("updatedAt", OffsetDateTime.now().toString());
        run.put("lastRegeneratedSceneId", sceneId);
        run.put("lastBillingAction", Map.of(
                "type", replacingGeneratedClip ? "PAID_SCENE_RERUN" : "INITIAL_OR_RESUMED_SCENE",
                "sceneId", sceneId,
                "billingConsent", booleanValue(inputPayload.get("billingConsent"), false),
                "recordedAt", OffsetDateTime.now().toString()
        ));
        run.put("message", "Shot " + firstText(scene.get("sceneNumber"), sceneId) + " queued for " + providerLabel + " generation.");

        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_SCREENPLAY_VIDEO_SCENE_REGENERATE,
                safeTenantId,
                safeUserId,
                script.getProjectId(),
                inputPayload
        );
        return generationJobService.updateGenerationJobProgress(
                job.getId(),
                8,
                "Queued screenplay shot video",
                owner.outputPayload(run, "Shot " + firstText(scene.get("sceneNumber"), sceneId) + " queued for " + providerLabel + " generation.")
        );
    }
}
