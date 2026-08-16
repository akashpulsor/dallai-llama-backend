package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.repository.CreatorGenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's media-URL refresh and audio-state merge cluster -
 * previously the plan's "VideoRunHydrationService" row. Called from loadRun() and
 * hydrateVideoRunForResponse(), both of which stay in ScreenplayVideoService (too foundational -
 * loadRun alone is called from dozens of places across the class and from every owner-scoped
 * extraction so far) and now delegate their media/audio refresh work here.
 *
 * <p>Deliberately excludes reconcilePreparedRunScenes/enrichScenesWithStoryboardReferences, which
 * loadRun and hydrateVideoRunForResponse also call - that's scene-reconciliation, a separate,
 * larger concern with its own call graph, not "hydration" in the media-URL/audio-state sense this
 * class covers. Left for a future row rather than folded in here to avoid scope creep.
 *
 * <p>Same package, owner back-reference, ProviderRequestBuilder shape - refreshVideoAssetReference
 * (video assets) moves here since it's exclusive to this cluster, but refreshAudioAssetReference
 * (audio assets) stays on ScreenplayVideoService and is reached through owner, because
 * DialogueVoiceCloner already depends on it directly for the same "refresh a stored asset's signed
 * URL" concern.
 */
final class VideoRunHydrator {

    private static final Logger log = LoggerFactory.getLogger(VideoRunHydrator.class);

    private final ScreenplayVideoService owner;
    private final CreatorGenerationJobRepository generationJobRepository;
    private final AssetStorageService assetStorageService;

    VideoRunHydrator(
            ScreenplayVideoService owner,
            CreatorGenerationJobRepository generationJobRepository,
            AssetStorageService assetStorageService
    ) {
        this.owner = owner;
        this.generationJobRepository = generationJobRepository;
        this.assetStorageService = assetStorageService;
    }

    void mergeLatestAudioState(Map<String, Object> run, String tenantId, String userId, String runId) {
        if (run == null || run.isEmpty()) {
            return;
        }
        generationJobRepository
                .findLatestScreenplayVideoAudioRunJob(defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), runId)
                .map(CreatorGenerationJob::getOutputPayload)
                .map(payload -> firstNonEmptyMap(payload.get("videoRun"), payload.get("screenplayVideoRun"), payload))
                .filter(this::hasAudioState)
                .ifPresent(audioRun -> {
                    owner.copyIfPresent(run, audioRun, "dialogueAudio");
                    owner.copyIfPresent(run, audioRun, "voiceTrack");
                    owner.copyIfPresent(run, audioRun, "audioPack");
                    owner.copyIfPresent(run, audioRun, "audioAssets");
                    owner.copyIfPresent(run, audioRun, "audioProductionPlan");
                    owner.copyIfPresent(run, audioRun, "freeMusicSelectionPlan");
                    owner.copyIfPresent(run, audioRun, "backgroundMusicSelection");
                    owner.copyIfPresent(run, audioRun, "backgroundMusic");
                    owner.copyIfPresent(run, audioRun, "musicTrack");
                    owner.copyIfPresent(run, audioRun, "combinedDialogueAudio");
                    owner.copyIfPresent(run, audioRun, "combinedSceneDialogueAudio");
                    owner.copyIfPresent(run, audioRun, "combinedDialogueTrack");
                    owner.copyIfPresent(run, audioRun, "combinedDialogueSummary");
                    run.put("audioStateRecoveredFromJob", true);
                });
    }

    private boolean hasAudioState(Map<String, Object> run) {
        return run != null && (
                !firstMap(run.get("dialogueAudio")).isEmpty()
                        || !firstMap(run.get("audioPack")).isEmpty()
                        || !mapListValue(run.get("audioAssets")).isEmpty()
                        || !firstText(run.get("voiceTrack")).isBlank()
                        || !firstMap(run.get("backgroundMusic")).isEmpty()
                        || !firstMap(run.get("backgroundMusicSelection")).isEmpty()
                        || !firstMap(run.get("combinedDialogueAudio")).isEmpty()
                        || !firstMap(run.get("combinedSceneDialogueAudio")).isEmpty()
                        || !firstMap(run.get("freeMusicSelectionPlan")).isEmpty()
        );
    }

    void invalidateStaleCombinedDialogueAudio(
            Map<String, Object> run,
            List<Map<String, Object>> scenes
    ) {
        Map<String, Object> combined = firstNonEmptyMap(
                run == null ? null : run.get("combinedDialogueAudio"),
                run == null ? null : run.get("combinedSceneDialogueAudio")
        );
        if (combined.isEmpty()) {
            return;
        }
        String expectedFingerprint = owner.sceneDialogueAudioFingerprint(
                owner.sceneDialogueAudioInputs(scenes),
                scenes == null ? 0 : scenes.size()
        );
        String storedFingerprint = firstText(
                combined.get("combinedFingerprint"),
                combined.get("dialogueFingerprint"),
                firstMap(combined.get("metadata")).get("dialogueFingerprint")
        );
        if (expectedFingerprint.isBlank() || !expectedFingerprint.equals(storedFingerprint)) {
            owner.clearCombinedDialogueAudio(run);
        }
    }

    void refreshRunMediaUrls(Map<String, Object> run) {
        if (run == null || run.isEmpty()) {
            return;
        }
        List<Map<String, Object>> scenes = refreshSceneVideoUrls(run.get("scenes"));
        if (!scenes.isEmpty()) {
            run.put("scenes", scenes);
            run.put("sceneClips", scenes);
        }
        Map<String, Object> finalVideo = refreshVideoAssetReference(run.get("finalVideo"));
        List<Map<String, Object>> finalVariants = refreshVideoAssetList(run.get("finalVideoVariants"));
        if (!finalVariants.isEmpty()) {
            run.put("finalVideoVariants", finalVariants);
            finalVideo = preferredFinalVideo(finalVariants, finalVideo);
        }
        if (!finalVideo.isEmpty()) {
            run.put("finalVideo", finalVideo);
            String finalUrl = firstText(finalVideo.get("videoUrl"), finalVideo.get("signedUrl"), finalVideo.get("publicUrl"));
            if (!finalUrl.isBlank()) {
                run.put("videoUrl", finalUrl);
                run.put("publicUrl", finalUrl);
                run.put("finalVideoUrl", finalUrl);
            }
        }
        Map<String, Object> renderManifest = copyMap(run.get("renderManifest"));
        if (!renderManifest.isEmpty()) {
            Map<String, Object> manifestFinal = refreshVideoAssetReference(renderManifest.get("finalVideo"));
            List<Map<String, Object>> manifestVariants = refreshVideoAssetList(renderManifest.get("finalVideoVariants"));
            if (!manifestVariants.isEmpty()) {
                renderManifest.put("finalVideoVariants", manifestVariants);
                manifestFinal = preferredFinalVideo(manifestVariants, manifestFinal);
            }
            if (!manifestFinal.isEmpty()) {
                renderManifest.put("finalVideo", manifestFinal);
                renderManifest.put("finalVideoUrl", firstText(manifestFinal.get("videoUrl"), manifestFinal.get("signedUrl"), manifestFinal.get("publicUrl")));
            }
            run.put("renderManifest", renderManifest);
        }
        refreshAudioAssetUrls(run);
    }

    private List<Map<String, Object>> refreshSceneVideoUrls(Object value) {
        List<Map<String, Object>> scenes = mapListValue(value);
        List<Map<String, Object>> refreshed = new ArrayList<>();
        for (Map<String, Object> scene : scenes) {
            Map<String, Object> refreshedScene = refreshVideoAssetReference(scene);
            Map<String, Object> dialogueAudio = owner.refreshAudioAssetReference(refreshedScene.get("dialogueAudio"));
            if (!dialogueAudio.isEmpty()) {
                refreshedScene.put("dialogueAudio", dialogueAudio);
                refreshedScene.put("voiceTrack", firstText(
                        dialogueAudio.get("assetUrl"),
                        dialogueAudio.get("signedUrl"),
                        dialogueAudio.get("publicUrl")
                ));
            }
            Map<String, Object> avatarPortrait = refreshVideoAssetReference(refreshedScene.get("avatarPortraitAsset"));
            if (!avatarPortrait.isEmpty()) {
                refreshedScene.put("avatarPortraitAsset", avatarPortrait);
                refreshedScene.put("avatarPortraitUrl", firstText(
                        avatarPortrait.get("assetUrl"),
                        avatarPortrait.get("signedUrl"),
                        avatarPortrait.get("publicUrl")
                ));
            }
            Map<String, Object> videoAsset = refreshVideoAssetReference(refreshedScene.get("videoAsset"));
            if (!videoAsset.isEmpty()) {
                refreshedScene.put("videoAsset", videoAsset);
                String videoUrl = firstText(videoAsset.get("videoUrl"), videoAsset.get("signedUrl"), videoAsset.get("publicUrl"));
                if (!videoUrl.isBlank()) {
                    refreshedScene.put("videoUrl", videoUrl);
                    refreshedScene.put("clipUrl", videoUrl);
                }
            }
            refreshed.add(refreshedScene);
        }
        return refreshed;
    }

    private List<Map<String, Object>> refreshVideoAssetList(Object value) {
        List<Map<String, Object>> assets = mapListValue(value);
        List<Map<String, Object>> refreshed = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            Map<String, Object> refreshedAsset = refreshVideoAssetReference(asset);
            if (!refreshedAsset.isEmpty()) {
                refreshed.add(refreshedAsset);
            }
        }
        return refreshed;
    }

    private Map<String, Object> refreshVideoAssetReference(Object value) {
        Map<String, Object> video = copyMap(value);
        if (video.isEmpty()) {
            return video;
        }
        String bucket = firstText(video.get("bucket"));
        String objectKey = firstText(video.get("objectKey"));
        if (bucket.isBlank() || objectKey.isBlank()) {
            return video;
        }
        try {
            String signedUrl = assetStorageService.signedUrl(bucket, objectKey, ScreenplayVideoService.SIGNED_URL_TTL);
            video.put("videoUrl", signedUrl);
            video.put("clipUrl", signedUrl);
            video.put("assetUrl", signedUrl);
            video.put("signedUrl", signedUrl);
            video.put("publicUrl", signedUrl);
            video.put("storageProvider", "minio");
            video.put("storageStatus", "SAVED_TO_MINIO");
            video.put("signedUrlTtlSeconds", ScreenplayVideoService.SIGNED_URL_TTL.toSeconds());
            video.put("signedUrlRefreshedAt", OffsetDateTime.now().toString());
            video.put("mediaAccessPolicy", "renew_on_authenticated_video_run_fetch");
            video.put("mediaUrlRefreshAfterSeconds", ScreenplayVideoService.MEDIA_URL_RENEWAL_SECONDS);
        } catch (RuntimeException ex) {
            log.warn("Could not refresh screenplay video signed URL bucket={} objectKey={} errorType={} errorMessage={}",
                    bucket, objectKey, ex.getClass().getSimpleName(), ex.getMessage());
        }
        return video;
    }

    private Map<String, Object> preferredFinalVideo(List<Map<String, Object>> variants, Map<String, Object> fallback) {
        for (Map<String, Object> variant : variants) {
            if ("CUSTOM_GENERATED_VOICE".equalsIgnoreCase(firstText(variant.get("audioVariant")))) {
                return variant;
            }
        }
        for (Map<String, Object> variant : variants) {
            if ("VIDEO_GENERATED_AUDIO".equalsIgnoreCase(firstText(variant.get("audioVariant")))) {
                return variant;
            }
        }
        return variants.isEmpty() ? fallback : variants.get(0);
    }

    private void refreshAudioAssetUrls(Map<String, Object> run) {
        if (run == null || run.isEmpty()) {
            return;
        }
        Map<String, Object> combinedDialogueAudio = owner.refreshAudioAssetReference(firstNonEmptyMap(
                run.get("combinedDialogueAudio"),
                run.get("combinedSceneDialogueAudio")
        ));
        if (!combinedDialogueAudio.isEmpty()) {
            run.put("combinedDialogueAudio", combinedDialogueAudio);
            run.put("combinedSceneDialogueAudio", combinedDialogueAudio);
            String combinedUrl = firstText(
                    combinedDialogueAudio.get("assetUrl"),
                    combinedDialogueAudio.get("signedUrl"),
                    combinedDialogueAudio.get("publicUrl")
            );
            if (!combinedUrl.isBlank()) {
                run.put("combinedDialogueTrack", combinedUrl);
            }
        }
        Map<String, Object> dialogueAudio = owner.refreshAudioAssetReference(run.get("dialogueAudio"));
        if (!dialogueAudio.isEmpty()) {
            run.put("dialogueAudio", dialogueAudio);
            String voiceUrl = firstText(dialogueAudio.get("assetUrl"), dialogueAudio.get("signedUrl"), dialogueAudio.get("publicUrl"));
            if (!voiceUrl.isBlank()) {
                run.put("voiceTrack", voiceUrl);
            }
        }
        Map<String, Object> backgroundMusic = refreshAudioContainer(run.get("backgroundMusic"));
        if (!backgroundMusic.isEmpty()) {
            run.put("backgroundMusic", backgroundMusic);
            Map<String, Object> musicAsset = firstNonEmptyMap(backgroundMusic.get("asset"), backgroundMusic);
            String musicUrl = firstText(musicAsset.get("assetUrl"), musicAsset.get("signedUrl"), musicAsset.get("publicUrl"));
            if (!musicUrl.isBlank()) {
                run.put("musicTrack", musicUrl);
            }
        }
        List<Map<String, Object>> audioAssets = refreshAudioAssetList(run.get("audioAssets"));
        if (!audioAssets.isEmpty()) {
            run.put("audioAssets", audioAssets);
        }
        Map<String, Object> audioPack = refreshAudioPack(run.get("audioPack"));
        if (!audioPack.isEmpty()) {
            run.put("audioPack", audioPack);
        }
        Map<String, Object> audioProductionPlan = refreshAudioPlan(run.get("audioProductionPlan"));
        if (!audioProductionPlan.isEmpty()) {
            run.put("audioProductionPlan", audioProductionPlan);
        }
        Map<String, Object> editingPlan = refreshAudioPlan(firstNonEmptyMap(run.get("editingPlan"), run.get("editorHandoffPlan")));
        if (!editingPlan.isEmpty()) {
            run.put("editingPlan", editingPlan);
            run.put("editorHandoffPlan", editingPlan);
        }
    }

    private Map<String, Object> refreshAudioPlan(Object value) {
        Map<String, Object> plan = copyMap(value);
        if (plan.isEmpty()) {
            return plan;
        }
        List<Map<String, Object>> generatedAssets = refreshAudioAssetList(plan.get("generatedAudioAssets"));
        if (!generatedAssets.isEmpty()) {
            plan.put("generatedAudioAssets", generatedAssets);
        }
        Map<String, Object> dialogue = refreshAudioContainer(plan.get("dialogue"));
        if (!dialogue.isEmpty()) {
            plan.put("dialogue", dialogue);
        }
        Map<String, Object> backgroundMusic = refreshAudioContainer(plan.get("backgroundMusic"));
        if (!backgroundMusic.isEmpty()) {
            plan.put("backgroundMusic", backgroundMusic);
        }
        Map<String, Object> audioPack = refreshAudioPack(plan.get("audioPack"));
        if (!audioPack.isEmpty()) {
            plan.put("audioPack", audioPack);
        }
        return plan;
    }

    private Map<String, Object> refreshAudioPack(Object value) {
        Map<String, Object> audioPack = copyMap(value);
        if (audioPack.isEmpty()) {
            return audioPack;
        }
        List<Map<String, Object>> assets = refreshAudioAssetList(audioPack.get("assets"));
        if (!assets.isEmpty()) {
            audioPack.put("assets", assets);
        }
        Map<String, Object> dialogue = refreshAudioContainer(audioPack.get("dialogue"));
        if (!dialogue.isEmpty()) {
            audioPack.put("dialogue", dialogue);
        }
        Map<String, Object> backgroundMusic = refreshAudioContainer(audioPack.get("backgroundMusic"));
        if (!backgroundMusic.isEmpty()) {
            audioPack.put("backgroundMusic", backgroundMusic);
        }
        return audioPack;
    }

    private Map<String, Object> refreshAudioContainer(Object value) {
        Map<String, Object> container = copyMap(value);
        if (container.isEmpty()) {
            return container;
        }
        Map<String, Object> asset = owner.refreshAudioAssetReference(container.get("asset"));
        if (!asset.isEmpty()) {
            container.put("asset", asset);
            return container;
        }
        return owner.refreshAudioAssetReference(container);
    }

    private List<Map<String, Object>> refreshAudioAssetList(Object value) {
        List<Map<String, Object>> assets = mapListValue(value);
        if (assets.isEmpty()) {
            return assets;
        }
        List<Map<String, Object>> refreshed = new ArrayList<>();
        for (Map<String, Object> asset : assets) {
            Map<String, Object> refreshedAsset = owner.refreshAudioAssetReference(asset);
            if (!refreshedAsset.isEmpty()) {
                refreshed.add(refreshedAsset);
            }
        }
        return refreshed;
    }
}
