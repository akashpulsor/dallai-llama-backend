package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.VideoAssetPersistenceService;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gives an already-generated clip the seconds its dialogue needs, without generating it again.
 *
 * <p>The cheap repair. A shot whose line overruns has two expensive answers -- render it again at a
 * longer duration, paying for the whole clip a second time and getting a different-looking shot, or
 * cut the line. This is the third: keep the clip that was paid for, add only the seconds that are
 * missing, and lay the full dialogue across both.
 *
 * <p>On shot-01-006's real numbers -- a four-second clip and a 9.2-second line -- regenerating bills
 * ten seconds and discards a clip the creator already accepted. Extending bills six. Holding the
 * last frame bills nothing at all.
 *
 * <h2>Two ways to make the tail</h2>
 * <ul>
 *   <li>{@link Mode#HOLD} -- freeze the final frame. No model call, no cost, and for a motion
 *       graphic or a held B-roll it is usually what was wanted anyway: the card stays up while the
 *       narrator finishes. On a moving live-action shot it reads as a freeze, which is the trade.</li>
 *   <li>{@link Mode#GENERATE} -- animate on from the final frame with a cheaper video model than the
 *       one that made the shot. The tail is a continuation, not a new idea, so it does not need the
 *       model the shot was worth paying for.</li>
 * </ul>
 *
 * <p>The join is a concat and a mux, both done here with the container's own ffmpeg, the same way
 * {@code FinalRenderService} assembles a project. Nothing is overwritten: the extended clip is a new
 * object, and the original stays where it was.
 */
@Slf4j
@Service
public class ClipTailExtensionService {

    public enum Mode {
        /** Freeze the last frame for the missing seconds. Costs nothing. */
        HOLD,
        /** Animate on from the last frame with a cheaper model. Costs those seconds only. */
        GENERATE,
        /**
         * Leave the picture exactly as it is and put the dubbed track on it, dropping whatever
         * audio the video model produced.
         *
         * <p>The simplest repair and the cheapest -- no model, no re-encode of the picture, just a
         * remux. For a shot generated on the native-audio path this is usually the whole fix: the
         * model was told to speak a line it had no room for, so what it produced is a fragment at
         * the end over ambience, and the dub is the take that was actually wanted.
         */
        REPLACE_AUDIO
    }

    private final LlmGatewayClient llmGatewayClient;
    private final VideoAssetPersistenceService assetPersistenceService;
    private final AudioDurationProbe durationProbe;
    private final String tailModel;
    private final long ffmpegTimeoutSeconds;
    private final String bucket;
    private final int maxTailSeconds;

    public ClipTailExtensionService(
            LlmGatewayClient llmGatewayClient,
            VideoAssetPersistenceService assetPersistenceService,
            AudioDurationProbe durationProbe,
            /** Deliberately not the shot's own model: a tail continues a picture that already
             * exists, so it does not need what the shot itself was worth paying for. */
            @Value("${video-gen.tail-extension.model:alibaba/wan-3.0-prime}") String tailModel,
            @Value("${video-gen.tail-extension.ffmpeg-timeout-seconds:300}") long ffmpegTimeoutSeconds,
            @Value("${video-gen.tail-extension.max-seconds:8}") int maxTailSeconds,
            @Value("${video-gen.minio.bucket:creator-assets}") String bucket
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.assetPersistenceService = assetPersistenceService;
        this.durationProbe = durationProbe;
        this.tailModel = tailModel;
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
        this.maxTailSeconds = maxTailSeconds;
        this.bucket = bucket;
    }

    /**
     * @param clipUrl     the generated shot as it stands.
     * @param audioUrl    the dialogue take that does not fit it.
     * @param tailSeconds how much longer the shot must be. Clamped to {@code max-seconds}: past
     *                    that the shot list is wrong and a tail is papering over it.
     */
    public Extended extend(UUID tenantId, UUID projectId, UUID jobId, String clipUrl, String audioUrl,
                           int tailSeconds, Mode mode, String continuationPrompt,
                           String resolution, String aspectRatio) {
        if (clipUrl == null || clipUrl.isBlank()) {
            throw VideoGenException.badRequest("There is no clip to extend");
        }
        if (tailSeconds <= 0 && mode != Mode.REPLACE_AUDIO) {
            throw VideoGenException.badRequest("A tail needs a length in seconds");
        }
        int seconds = Math.min(tailSeconds, maxTailSeconds);
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("tail-" + jobId);
            Path clip = workDir.resolve("clip.mp4");
            download(clipUrl, clip);

            // The last frame is the only thing the tail has to match. Taken from the clip itself
            // rather than from the shot's storyboard: what the tail must continue is what was
            // actually generated, which is not always what was planned.
            Path lastFrame = workDir.resolve("last.png");
            runFfmpeg(List.of("ffmpeg", "-y", "-sseof", "-0.05", "-i", clip.toString(),
                    "-vframes", "1", "-q:v", "2", lastFrame.toString()));
            if (!Files.exists(lastFrame) || Files.size(lastFrame) == 0) {
                throw VideoGenException.upstream("Could not read the clip's last frame");
            }

            // Nothing to join: the picture stands, only its audio changes.
            if (mode == Mode.REPLACE_AUDIO) {
                return remuxAudioOnly(workDir, clip, audioUrl, jobId);
            }

            Path tail = mode == Mode.GENERATE
                    ? generateTail(tenantId, projectId, jobId, workDir, lastFrame, seconds,
                            continuationPrompt, resolution, aspectRatio)
                    : holdTail(workDir, lastFrame, clip, seconds);

            // Concat, then lay the full dialogue over the joined picture. Re-encoded rather than
            // stream-copied: the tail comes from a different encoder than the shot, and -c copy
            // across mismatched streams is what produces a file that plays for two seconds and
            // stops.
            // The tail is scaled to the clip's exact dimensions before joining. concat refuses
            // mismatched sizes outright, and a model asked for "a few seconds" returns whatever
            // resolution its own defaults give -- which is not necessarily the 480p or 720p the shot
            // was generated at. setsar keeps the pixel aspect the same so the join does not stretch.
            String size = probeDimensions(clip);
            Path joined = workDir.resolve("joined.mp4");
            runFfmpeg(List.of("ffmpeg", "-y", "-i", clip.toString(), "-i", tail.toString(),
                    "-filter_complex",
                    "[1:v]scale=" + size + ":force_original_aspect_ratio=decrease,"
                            + "pad=" + size + ":(ow-iw)/2:(oh-ih)/2,setsar=1[t];"
                            + "[0:v]setsar=1[c];[c][t]concat=n=2:v=1:a=0[v]",
                    "-map", "[v]", "-c:v", "libx264", "-pix_fmt", "yuv420p", joined.toString()));

            Path finished = joined;
            if (audioUrl != null && !audioUrl.isBlank()) {
                Path audio = workDir.resolve("dialogue.mp3");
                download(audioUrl, audio);
                finished = workDir.resolve("final.mp4");
                // -shortest so a track marginally longer than the joined picture ends with it
                // rather than stretching the file past its own video.
                runFfmpeg(List.of("ffmpeg", "-y", "-i", joined.toString(), "-i", audio.toString(),
                        "-c:v", "copy", "-c:a", "aac", "-shortest", finished.toString()));
            }

            double finalSeconds = durationProbe.probeFile(finished);
            VideoAssetPersistenceService.PersistedAsset asset = assetPersistenceService.uploadFile(
                    bucket, "tail-extended/%s-%s.mp4".formatted(jobId, UUID.randomUUID()), finished);
            String url = assetPersistenceService.presignedUrl(asset.bucket(), asset.objectKey());
            log.info("Extended a clip's tail jobId={} mode={} added={}s finalLength={}s",
                    jobId, mode, seconds, String.format(Locale.ROOT, "%.2f", finalSeconds));
            return new Extended(url, asset.bucket(), asset.objectKey(), finalSeconds, seconds, mode.name());
        } catch (VideoGenException ex) {
            throw ex;
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not extend the clip: " + ex.getMessage(), ex);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /**
     * The clip untouched, carrying the dubbed track instead of the model's own audio.
     *
     * <p>{@code -map 0:v -map 1:a} takes the picture from the clip and the sound from the dub, which
     * is what drops the native audio rather than mixing the two: a line the model half-spoke
     * underneath the line it should have spoken is worse than either alone. The video stream is
     * copied, not re-encoded, so the picture is bit-identical to what was generated and paid for.
     */
    private Extended remuxAudioOnly(Path workDir, Path clip, String audioUrl, UUID jobId) throws Exception {
        if (audioUrl == null || audioUrl.isBlank()) {
            throw VideoGenException.badRequest("There is no dubbed audio to put on this clip");
        }
        Path audio = workDir.resolve("dialogue.mp3");
        download(audioUrl, audio);
        Path finished = workDir.resolve("remuxed.mp4");
        runFfmpeg(List.of("ffmpeg", "-y", "-i", clip.toString(), "-i", audio.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
                "-shortest", finished.toString()));
        double finalSeconds = durationProbe.probeFile(finished);
        VideoAssetPersistenceService.PersistedAsset asset = assetPersistenceService.uploadFile(
                bucket, "audio-replaced/%s-%s.mp4".formatted(jobId, UUID.randomUUID()), finished);
        String url = assetPersistenceService.presignedUrl(asset.bucket(), asset.objectKey());
        log.info("Replaced a clip's audio with its dubbed take jobId={} length={}s",
                jobId, String.format(Locale.ROOT, "%.2f", finalSeconds));
        return new Extended(url, asset.bucket(), asset.objectKey(), finalSeconds, 0, Mode.REPLACE_AUDIO.name());
    }

    /** A still held for the missing seconds, at the clip's own frame rate so the join is seamless. */
    private Path holdTail(Path workDir, Path lastFrame, Path clip, int seconds) throws Exception {
        Path tail = workDir.resolve("tail.mp4");
        String fps = probeFrameRate(clip);
        runFfmpeg(List.of("ffmpeg", "-y", "-loop", "1", "-i", lastFrame.toString(),
                "-t", String.valueOf(seconds), "-r", fps,
                // Same frame the clip ends on, so this is already the right size -- stated anyway so
                // an odd-dimensioned source cannot produce a tail libx264 refuses to encode.
                "-vf", "scale=" + probeDimensions(clip) + ",setsar=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", tail.toString()));
        return tail;
    }

    /** A continuation animated from the last frame by the cheaper model. */
    private Path generateTail(UUID tenantId, UUID projectId, UUID jobId, Path workDir, Path lastFrame,
                              int seconds, String continuationPrompt,
                              String resolution, String aspectRatio) throws Exception {
        VideoAssetPersistenceService.PersistedAsset frameAsset = assetPersistenceService.uploadFile(
                bucket, "tail-frames/%s-%s.png".formatted(jobId, UUID.randomUUID()), lastFrame);
        String frameUrl = assetPersistenceService.presignedUrl(frameAsset.bucket(), frameAsset.objectKey());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("duration_seconds", seconds);
        params.put("reference_image_urls", List.of(frameUrl));
        params.put("generate_audio", Boolean.FALSE);
        // The shot's own resolution and aspect, so the tail is GENERATED at the size it has to join
        // rather than produced at the model's default and resized afterwards. Scaling up from a
        // smaller frame softens exactly the seconds the eye is still on, and the padding that would
        // otherwise rescue a mismatched aspect would letterbox them. The scale/pad at the join stays
        // as a safety net for a model that ignores what it was asked for.
        if (resolution != null && !resolution.isBlank()) {
            params.put("resolution", resolution);
        }
        if (aspectRatio != null && !aspectRatio.isBlank()) {
            params.put("aspect_ratio", aspectRatio);
        }
        String prompt = continuationPrompt == null || continuationPrompt.isBlank()
                // Says "carry on", not "do something": a tail that introduces a new idea is worse
                // than the silence it was meant to cover.
                ? "Continue this exact shot from where it is, holding the same framing, subject, "
                  + "lighting and style. Very subtle continued motion only. Introduce nothing new."
                : continuationPrompt;
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId == null ? null : tenantId.toString(),
                "tail-extend-" + jobId + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(tailModel, List.of(new LlmGatewayMessage("user", prompt)),
                        params, null, null, projectId));
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw VideoGenException.upstream("The tail model returned no clip");
        }
        Path tail = workDir.resolve("tail.mp4");
        download(response.response(), tail);
        return tail;
    }

    /** The clip's own width:height, so the tail can be made to match it exactly. */
    private String probeDimensions(Path clip) {
        try {
            Process process = new ProcessBuilder(List.of("ffprobe", "-v", "error",
                    "-select_streams", "v:0", "-show_entries", "stream=width,height",
                    "-of", "csv=p=0:s=x", clip.toString()))
                    .redirectErrorStream(true).start();
            String out;
            try (var in = process.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            String dims = out.lines().findFirst().orElse("").trim();
            // A shape like 832x480. Anything else and we would be handing ffmpeg a broken filter,
            // so fall back to a common portrait size rather than fail the repair.
            return dims.matches("[0-9]+x[0-9]+") ? dims.replace("x", ":") : "720:1280";
        } catch (Exception ex) {
            return "720:1280";
        }
    }

    private String probeFrameRate(Path clip) {
        try {
            Process process = new ProcessBuilder(List.of("ffprobe", "-v", "error",
                    "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate",
                    "-of", "default=noprint_wrappers=1:nokey=1", clip.toString()))
                    .redirectErrorStream(true).start();
            String out;
            try (var in = process.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            String rate = out.lines().findFirst().orElse("").trim();
            return rate.isBlank() || rate.startsWith("0") ? "30" : rate;
        } catch (Exception ex) {
            return "30";
        }
    }

    private void download(String url, Path target) throws Exception {
        try (var in = java.net.URI.create(url).toURL().openStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runFfmpeg(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!process.waitFor(ffmpegTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg exited " + process.exitValue()
                    + ": " + output.lines().reduce((a, b) -> b).orElse(""));
        }
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temp dir; a leftover file is not worth failing a finished clip over.
                }
            });
        } catch (Exception ignored) {
            // As above.
        }
    }

    /** @param addedSeconds what was actually added, after clamping. */
    public record Extended(String url, String bucket, String objectKey, double finalSeconds,
                           int addedSeconds, String mode) {
        public BigDecimal finalSecondsRounded() {
            return BigDecimal.valueOf(Math.round(finalSeconds * 100) / 100.0);
        }
    }
}
