package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's ffmpeg scene-clip merge / custom-voice-variant render
 * cluster - previously the plan's "FinalVideoRenderService" row: concatenating accepted scene
 * clips into the final video, and re-muxing a custom-cloned-voice audio track (with optional
 * ducked background music) over the same picture as a second selectable variant.
 *
 * <p>Same package, owner back-reference - runFfmpeg/tail/readQuietly/hasStoredAssetLocation/
 * audioFileExtension/safeSlug/deleteQuietly are all reached through owner because they're shared
 * with DialogueVoiceCloner.combineSceneDialogueAudio's ffmpeg half (runFfmpeg itself calls tail/
 * readQuietly internally, which is what pins those two to staying on ScreenplayVideoService
 * rather than moving here). generatedMusicAsset is reached through owner too - it's also used by
 * runAudioPackJob, which stays on ScreenplayVideoService.
 */
final class FinalVideoRenderer {

    private static final Logger log = LoggerFactory.getLogger(FinalVideoRenderer.class);

    private final ScreenplayVideoService owner;
    private final AssetStorageService assetStorageService;

    FinalVideoRenderer(ScreenplayVideoService owner, AssetStorageService assetStorageService) {
        this.owner = owner;
        this.assetStorageService = assetStorageService;
    }

    boolean hasMergeableClip(Map<String, Object> input) {
        return input != null
                && !firstText(input.get("bucket")).isBlank()
                && !firstText(input.get("objectKey")).isBlank();
    }

    boolean acceptedScenesCoverAll(List<Map<String, Object>> scenes, List<String> acceptedSceneIds) {
        if (scenes == null || scenes.isEmpty()) {
            return false;
        }
        if (acceptedSceneIds == null || acceptedSceneIds.isEmpty()) {
            return false;
        }
        return scenes.stream()
                .allMatch(scene -> {
                    String id = firstText(scene.get("id"), scene.get("sceneId"), scene.get("scene_id"));
                    String sceneNumber = stringValue(firstValue(scene.get("sceneNumber"), scene.get("shotNumber")), "");
                    return acceptedSceneIds.contains(id)
                            || (!sceneNumber.isBlank() && acceptedSceneIds.contains(sceneNumber))
                            || Boolean.TRUE.equals(scene.get("accepted"))
                            || Boolean.TRUE.equals(scene.get("clipAccepted"));
                });
    }

    Map<String, Object> mergeSceneClips(
            CreatorScript script,
            UUID runId,
            List<Map<String, Object>> scenes,
            Map<String, Object> run,
            Map<String, Object> request
    ) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("screenplay-video-" + runId + "-");
            List<Path> clipPaths = new ArrayList<>();
            for (int index = 0; index < scenes.size(); index++) {
                Map<String, Object> scene = scenes.get(index);
                String bucket = firstText(scene.get("bucket"));
                String objectKey = firstText(scene.get("objectKey"));
                if (bucket.isBlank() || objectKey.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scene " + (index + 1) + " is missing a generated clip object.");
                }
                Path clipPath = workDir.resolve("%03d-%s.mp4".formatted(index + 1, owner.safeSlug(firstText(scene.get("id"), scene.get("sceneId"), "scene"))));
                assetStorageService.downloadObjectToPath(bucket, objectKey, clipPath);
                clipPaths.add(clipPath);
            }
            Path concatFile = workDir.resolve("concat.txt");
            Path output = workDir.resolve("final.mp4");
            Path logPath = workDir.resolve("ffmpeg-concat.log");
            Files.writeString(concatFile, concatFile(clipPaths));

            List<String> command = new ArrayList<>(List.of(
                    "ffmpeg",
                    "-y",
                    "-f",
                    "concat",
                    "-safe",
                    "0",
                    "-i",
                    concatFile.toString()
            ));
            int requestedTargetDuration = positiveInt(firstValue(request.get("targetDurationSeconds"), request.get("durationSeconds")), 0);
            boolean trimToTargetDuration = booleanValue(request.get("trimToTargetDuration"), false);
            int targetDuration = trimToTargetDuration ? requestedTargetDuration : 0;
            if (requestedTargetDuration > 0 && !trimToTargetDuration) {
                log.info("Ignoring final merge duration cap to preserve every generated scene clip scriptId={} runId={} requestedTargetDurationSeconds={} sceneCount={}",
                        script == null ? null : script.getId(), runId, requestedTargetDuration, scenes.size());
            }
            if (targetDuration > 0) {
                command.add("-t");
                command.add(String.valueOf(targetDuration));
            }
            command.addAll(List.of(
                    "-c:v",
                    "libx264",
                    "-c:a",
                    "aac",
                    "-pix_fmt",
                    "yuv420p",
                    "-movflags",
                    "+faststart",
                    output.toString()
            ));
            owner.runFfmpeg(command, logPath, "Screenplay final video merge failed");

            Map<String, Object> nativeVideoAudio = storeFinalVideoVariant(
                    script,
                    runId,
                    output,
                    "VIDEO_GENERATED_AUDIO",
                    "Video generated audio",
                    scenes.size(),
                    targetDuration,
                    owner.tail(owner.readQuietly(logPath), 1600)
            );
            List<Map<String, Object>> variants = new ArrayList<>();
            variants.add(nativeVideoAudio);

            Map<String, Object> customVoiceAudio = new LinkedHashMap<>();
            String customVoiceUnavailableReason = "Generate and save the dialogue voiceover before final merge to create the custom-voice version.";
            try {
                customVoiceAudio = renderCustomVoiceVariant(
                        script,
                        runId,
                        output,
                        run,
                        workDir,
                        scenes.size(),
                        targetDuration
                );
            } catch (ResponseStatusException ex) {
                customVoiceUnavailableReason = firstText(ex.getReason(), "The custom voice version could not be rendered.");
                log.warn("Could not render custom voice final video scriptId={} runId={} errorMessage={}",
                        script == null ? null : script.getId(), runId, customVoiceUnavailableReason);
            }
            if (!customVoiceAudio.isEmpty()) {
                variants.add(customVoiceAudio);
            }

            Map<String, Object> primary = new LinkedHashMap<>(customVoiceAudio.isEmpty() ? nativeVideoAudio : customVoiceAudio);
            primary.put("variants", variants);
            primary.put("defaultAudioVariant", primary.get("audioVariant"));
            primary.put("customVoiceAvailable", !customVoiceAudio.isEmpty());
            if (customVoiceAudio.isEmpty()) {
                primary.put("customVoiceUnavailableReason", customVoiceUnavailableReason);
            }
            return primary;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not render screenplay final video.", ex);
        } finally {
            owner.deleteQuietly(workDir);
        }
    }

    private Map<String, Object> renderCustomVoiceVariant(
            CreatorScript script,
            UUID runId,
            Path nativeVideo,
            Map<String, Object> run,
            Path workDir,
            int sceneCount,
            int targetDuration
    ) {
        Map<String, Object> dialogueAsset = customVoiceAsset(run);
        String dialogueBucket = firstText(dialogueAsset.get("bucket"));
        String dialogueObjectKey = firstText(dialogueAsset.get("objectKey"));
        if (dialogueBucket.isBlank() || dialogueObjectKey.isBlank()) {
            return new LinkedHashMap<>();
        }

        try {
            Path dialoguePath = workDir.resolve("custom-dialogue." + owner.audioFileExtension(firstText(dialogueAsset.get("contentType"), "audio/mpeg")));
            assetStorageService.downloadObjectToPath(dialogueBucket, dialogueObjectKey, dialoguePath);

            Map<String, Object> musicAsset = owner.generatedMusicAsset(run);
            String musicBucket = firstText(musicAsset.get("bucket"));
            String musicObjectKey = firstText(musicAsset.get("objectKey"));
            Path output = workDir.resolve("final-custom-voice.mp4");
            Path logPath = workDir.resolve("ffmpeg-custom-voice.log");
            List<String> command = new ArrayList<>(List.of(
                    "ffmpeg",
                    "-y",
                    "-i",
                    nativeVideo.toString(),
                    "-i",
                    dialoguePath.toString()
            ));
            boolean hasMusic = !musicBucket.isBlank() && !musicObjectKey.isBlank();
            if (hasMusic) {
                Path musicPath = workDir.resolve("background-music." + owner.audioFileExtension(firstText(musicAsset.get("contentType"), "audio/mpeg")));
                assetStorageService.downloadObjectToPath(musicBucket, musicObjectKey, musicPath);
                command.add("-stream_loop");
                command.add("-1");
                command.add("-i");
                command.add(musicPath.toString());
                command.add("-filter_complex");
                command.add("[1:a]apad,volume=1.0,asplit=2[voice][voice_sidechain];"
                        + "[2:a]apad,volume=0.20[music];"
                        + "[music][voice_sidechain]sidechaincompress=threshold=0.02:ratio=8:attack=20:release=250[ducked_music];"
                        + "[voice][ducked_music]amix=inputs=2:duration=first:normalize=0[aout]");
            } else {
                command.add("-filter_complex");
                command.add("[1:a]apad,volume=1.0[aout]");
            }
            command.addAll(List.of(
                    "-map",
                    "0:v:0",
                    "-map",
                    "[aout]",
                    "-c:v",
                    "copy",
                    "-c:a",
                    "aac",
                    "-shortest",
                    "-movflags",
                    "+faststart",
                    output.toString()
            ));
            owner.runFfmpeg(command, logPath, "Screenplay custom voice final render failed");
            Map<String, Object> customVoiceVideo = storeFinalVideoVariant(
                    script,
                    runId,
                    output,
                    "CUSTOM_GENERATED_VOICE",
                    hasMusic ? "Custom generated voice with ducked AI background music" : "Custom generated voice",
                    sceneCount,
                    targetDuration,
                    owner.tail(owner.readQuietly(logPath), 1600)
            );
            customVoiceVideo.put("dialogueAssetId", dialogueAsset.get("assetId"));
            customVoiceVideo.put("backgroundMusicIncluded", hasMusic);
            return customVoiceVideo;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create the custom-voice final video.", ex);
        }
    }

    private Map<String, Object> storeFinalVideoVariant(
            CreatorScript script,
            UUID runId,
            Path output,
            String audioVariant,
            String audioVariantLabel,
            int sceneCount,
            int targetDuration,
            String ffmpegLogTail
    ) throws IOException {
        AssetStorageService.StoredObject stored;
        try (InputStream input = Files.newInputStream(output)) {
            stored = assetStorageService.uploadCreatorAssetFromStream(
                    finalVideoObjectKey(script, runId, audioVariant),
                    input,
                    "video/mp4",
                    ScreenplayVideoService.SIGNED_URL_TTL
            );
        }
        Map<String, Object> finalVideo = new LinkedHashMap<>();
        finalVideo.put("bucket", stored.bucket());
        finalVideo.put("objectKey", stored.objectKey());
        finalVideo.put("contentType", stored.contentType());
        finalVideo.put("sizeBytes", stored.sizeBytes());
        finalVideo.put("videoUrl", stored.signedUrl());
        finalVideo.put("signedUrl", stored.signedUrl());
        finalVideo.put("publicUrl", stored.signedUrl());
        finalVideo.put("audioVariant", audioVariant);
        finalVideo.put("audioVariantLabel", audioVariantLabel);
        finalVideo.put("sceneCount", sceneCount);
        finalVideo.put("renderer", "local_ffmpeg_concat");
        finalVideo.put("targetDurationSeconds", targetDuration);
        finalVideo.put("renderedAt", OffsetDateTime.now().toString());
        finalVideo.put("ffmpegLogTail", ffmpegLogTail);
        return finalVideo;
    }

    private Map<String, Object> customVoiceAsset(Map<String, Object> run) {
        Map<String, Object> direct = firstNonEmptyMap(
                run == null ? null : run.get("combinedDialogueAudio"),
                run == null ? null : run.get("combinedSceneDialogueAudio"),
                run == null ? null : run.get("dialogueAudio"),
                firstMap(firstMap(run == null ? null : run.get("audioPack")).get("dialogue")).get("asset"),
                firstMap(firstMap(run == null ? null : run.get("audioProductionPlan")).get("dialogue")).get("asset")
        );
        if (owner.hasStoredAssetLocation(direct)) {
            return direct;
        }
        for (Map<String, Object> asset : mapListValue(run == null ? null : run.get("audioAssets"))) {
            String layerType = firstText(asset.get("layerType"), asset.get("assetKind"), asset.get("assetType")).toLowerCase(Locale.ROOT);
            if ((layerType.contains("voice") || layerType.contains("dialogue")) && owner.hasStoredAssetLocation(asset)) {
                return asset;
            }
        }
        return new LinkedHashMap<>();
    }

    private String concatFile(List<Path> paths) {
        return paths.stream()
                .map(path -> "file '" + path.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''") + "'")
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private String finalVideoObjectKey(CreatorScript script, UUID runId, String audioVariant) {
        return "screenplay-videos/%s/%s/final/%s-%s.mp4".formatted(
                script.getId(),
                runId,
                owner.safeSlug(firstText(audioVariant, "final")),
                UUID.randomUUID()
        );
    }
}
