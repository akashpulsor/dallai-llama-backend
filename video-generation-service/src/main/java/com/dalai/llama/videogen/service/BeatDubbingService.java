package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.service.dialoguefit.DialogueFitMath;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayLanguageSelection;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.sceneenergy.SceneEnergyDirective;
import com.dalai.llama.videogen.service.sceneenergy.SceneEnergyStrategyResolver;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The "auto-dub" step: turns a shot's {@link DialogueBeat} list into one combined, beat-timed
 * cloned-voice audio track (one voice-clone+synthesize call, not one per beat -- MiniMax's
 * documented {@code <#x#>} pause-marker syntax encodes the gaps between beats directly in the
 * text, so a single fused call produces audio whose internal timing already matches the beats'
 * absolute positions), then muxes that track onto the already-generated silent video via fal.ai's
 * hosted {@code fal-ai/ffmpeg-api/merge-audio-video} (one call, offset-aware -- no ffmpeg
 * infrastructure needed in this service). Two provider calls total per shot, regardless of beat
 * count.
 *
 * <p>Duration/timing precision here is a best-effort bet, not a guarantee -- see the design doc's
 * risk #1: Seedance's silent mouth-shaping and this synthesized track are generated independently,
 * so how well they actually land in sync is exactly what {@code ShotGenerationOrchestrator}'s
 * post-dub duration check (and, if that fails, post-production's lip-sync fallback) exists for.
 */
@Slf4j
@Component
public class BeatDubbingService {

    private final LlmGatewayClient llmGatewayClient;
    private final SceneEnergyStrategyResolver sceneEnergyStrategyResolver;
    private final String voiceCloneModel;
    private final String ttsModel;
    private final String mergeModel;
    private final VideoAssetPersistenceService assetPersistenceService;
    private final com.dalai.llama.videogen.service.dialoguefit.AudioDurationProbe durationProbe;
    /** Past this, speeding speech up to fit stops producing something worth listening to. */
    private final double maxSpeedUp;
    private final long ffmpegTimeoutSeconds;
    private final String defaultDubBucket;

    public BeatDubbingService(
            LlmGatewayClient llmGatewayClient,
            SceneEnergyStrategyResolver sceneEnergyStrategyResolver,
            @Value("${video-gen.llm-gateway.default-voice-clone-model}") String voiceCloneModel,
            @Value("${video-gen.llm-gateway.default-tts-model}") String ttsModel,
            @Value("${video-gen.llm-gateway.default-audio-video-merge-model}") String mergeModel,
            VideoAssetPersistenceService assetPersistenceService,
            com.dalai.llama.videogen.service.dialoguefit.AudioDurationProbe durationProbe,
            @Value("${video-gen.dubbing.max-speed-up:1.5}") double maxSpeedUp,
            @Value("${video-gen.dubbing.ffmpeg-timeout-seconds:300}") long ffmpegTimeoutSeconds,
            @Value("${video-gen.dubbing.fitted-audio-bucket:creator-assets}") String defaultDubBucket
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.sceneEnergyStrategyResolver = sceneEnergyStrategyResolver;
        this.voiceCloneModel = voiceCloneModel;
        this.ttsModel = ttsModel;
        this.mergeModel = mergeModel;
        this.assetPersistenceService = assetPersistenceService;
        this.durationProbe = durationProbe;
        this.maxSpeedUp = maxSpeedUp;
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
        this.defaultDubBucket = defaultDubBucket;
    }

    /** True only when every beat resolved a cast voice -- a previously prepared clone, a raw
     * sample reference, or a stock built-in voice id. A shot with any beat missing all three falls back to normal
     * native-audio generation for the whole shot rather than partially dubbing (see {@code
     * DialogueBeat}'s javadoc on pre-production-service's side for why: multi-character beats
     * aren't independently voiced yet in this pass). */
    public boolean canAutoDub(List<DialogueBeat> beats) {
        return beats != null && !beats.isEmpty() && beats.stream().allMatch(BeatDubbingService::hasVoice);
    }

    private static boolean hasVoice(DialogueBeat beat) {
        return hasText(beat.clonedVoiceId())
                || hasText(beat.voiceReferenceUrl())
                || hasText(beat.builtinVoiceId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public DubResult dub(String tenantId, UUID jobId, UUID projectId, List<DialogueBeat> beats, String silentVideoUrl) {
        return dub(tenantId, jobId, projectId, beats, silentVideoUrl, null, null, null, null);
    }

    /** Pre-fps arity, kept so existing callers compile. Falls back to the assumed frame rate for
     * the gap and offset snapping, same as a shot whose plan states none. */
    public DubResult dub(String tenantId, UUID jobId, UUID projectId, List<DialogueBeat> beats, String silentVideoUrl,
                         String voiceCloneModelOverride, String ttsModelOverride, Integer shotDurationSeconds) {
        return dub(tenantId, jobId, projectId, beats, silentVideoUrl, voiceCloneModelOverride, ttsModelOverride,
                shotDurationSeconds, null);
    }

    /** {@code voiceCloneModelOverride}: a project's picked model (from llm-gateway's real
     * model_master, type=voice_clone) instead of this service's own configured default -- null
     * uses the default. Only a MiniMax-family model gets the {@code <#x#>} pause-marker text
     * (its own documented syntax, verified against fal-ai/minimax/voice-clone) in a single fused
     * clone+synthesize call; ElevenLabs has no such marker and no fused endpoint, so it goes
     * through clone-then-TTS as two calls instead, with the beats' text plainly concatenated (no
     * literal marker characters spoken aloud). Beat-to-beat gap timing is best-effort only for
     * ElevenLabs (no gap encoding at all), same "best-effort bet, not a guarantee" this class's
     * own class-doc already states for MiniMax's timing overall. {@code ttsModelOverride}: same
     * pin shape, type=tts -- which model actually speaks (clone-then-TTS's second call, and the
     * built-in-voice direct call), and via {@link SceneEnergyStrategyResolver} which mechanism
     * conveys this shot's emotion to it. */
    public DubResult dub(String tenantId, UUID jobId, UUID projectId, List<DialogueBeat> beats, String silentVideoUrl,
                          String voiceCloneModelOverride, String ttsModelOverride, Integer shotDurationSeconds,
                          Integer shotFps) {
        List<DialogueBeat> sorted = beats.stream()
                .sorted(Comparator.comparing(DialogueBeat::startSeconds))
                .toList();
        String referenceAudioUrl = sorted.get(0).voiceReferenceUrl();
        String clonedVoiceId = sorted.get(0).clonedVoiceId();
        String builtinVoiceId = sorted.get(0).builtinVoiceId();
        // A prepared clone and a built-in voice both already are usable provider voice_ids. They
        // therefore take the same direct-to-TTS path; cloning happens only for an unprepared raw sample.
        boolean usePreparedClone = hasText(clonedVoiceId);
        boolean useBuiltinVoice = !usePreparedClone && !hasText(referenceAudioUrl) && hasText(builtinVoiceId);
        boolean useDirectVoice = usePreparedClone || useBuiltinVoice;
        String directVoiceId = usePreparedClone ? clonedVoiceId : builtinVoiceId;

        String model = voiceCloneModelOverride == null || voiceCloneModelOverride.isBlank() ? voiceCloneModel : voiceCloneModelOverride;
        String resolvedTtsModel = ttsModelOverride == null || ttsModelOverride.isBlank() ? ttsModel : ttsModelOverride;
        boolean fused = !useDirectVoice && model.toLowerCase(Locale.ROOT).contains("minimax");
        String rawText = fused
                ? buildPausedText(sorted, shotFps)
                : sorted.stream().map(DialogueBeat::text).collect(java.util.stream.Collectors.joining(" "));
        // MiniMax's fused clone+synthesize is a different provider/request shape entirely (no
        // voice_settings concept) -- scene energy only applies to the ElevenLabs TTS paths.
        SceneEnergyDirective directive = fused
                ? SceneEnergyDirective.textOnly(rawText)
                : sceneEnergyStrategyResolver.resolve(tenantId, resolvedTtsModel, sorted.get(0).emotion(), rawText);
        String combinedText = directive.text();
        // Shot-level per the DialogueBeat javadoc; every beat in a shot shares the project's
        // dialogueLanguage. The fused MiniMax path has no language selection because it infers language.
        String languageCode = sorted.get(0).languageCode();

        LlmGatewayChatResponse synthesis = useDirectVoice
                ? directTtsSynthesize(tenantId, jobId, projectId, directVoiceId, combinedText, resolvedTtsModel, directive, languageCode)
                : fused
                        ? fusedCloneAndSynthesize(tenantId, jobId, projectId, model, referenceAudioUrl, combinedText)
                        : cloneThenSynthesize(tenantId, jobId, projectId, model, referenceAudioUrl, combinedText, resolvedTtsModel, directive, languageCode);
        if (synthesis == null || synthesis.response() == null || synthesis.response().isBlank()) {
            throw VideoGenException.upstream("llm-gateway returned no synthesized dialogue audio for job_id=" + jobId);
        }
        BigDecimal synthesisCost = synthesis.usage() == null ? BigDecimal.ZERO : synthesis.usage().cost();

        // Fit before merging, not after: the merge pins the track to the video's length, so an
        // over-long take is silently severed there rather than reported.
        // Against what the provider ACTUALLY returned, not what we asked for. Wan quantises a
        // request to a whole number of frames and hands back its own nearest length: shot-01-003
        // was dispatched at 5s and came back 4.7666s (143 frames at 30fps). Fitting the track to
        // the 5s we requested and then muxing it onto a 4.77s video means the merge severs the last
        // quarter second -- and reports dub_succeeded=true, because the merge did what it was told.
        // Probing costs nothing here: the file is about to be downloaded anyway.
        double actualVideoSeconds = durationProbe.probeUrl(silentVideoUrl);
        Integer fitTarget = shotDurationSeconds;
        if (actualVideoSeconds > 0) {
            // Floor, not round: the audio has to end INSIDE the picture. Half a frame over is still
            // over, and the mux resolves "over" by cutting.
            fitTarget = (int) Math.floor(actualVideoSeconds);
            if (shotDurationSeconds != null && fitTarget != shotDurationSeconds.intValue()) {
                log.info("Clip came back a different length than requested jobId={} requested={}s actual={}s"
                                + " -- fitting the dialogue to what was generated",
                        jobId, shotDurationSeconds, String.format(Locale.ROOT, "%.3f", actualVideoSeconds));
            }
        }
        String dialogueAudioUrl = fitAudioToShot(jobId, synthesis.response(), fitTarget, shotFps, actualVideoSeconds);

        // Snapped to a frame boundary before it becomes a mux offset. An offset of 1.37s at 24fps
        // falls between frames 32 and 33, and the encoder resolves that however it likes -- so the
        // alignment becomes a number nobody chose, differing between shots of the same project.
        BigDecimal startOffset = sorted.get(0).startSeconds();
        if (startOffset != null && startOffset.compareTo(BigDecimal.ZERO) > 0) {
            int frameRate = shotFps == null || shotFps <= 0 ? DialogueFitMath.ASSUMED_FPS : shotFps;
            startOffset = BigDecimal.valueOf(DialogueFitMath.snapUpToFrame(startOffset.doubleValue(), frameRate))
                    .setScale(3, java.math.RoundingMode.HALF_UP);
        }
        Map<String, Object> mergeParams = new LinkedHashMap<>();
        mergeParams.put("video_url", silentVideoUrl);
        mergeParams.put("audio_url", dialogueAudioUrl);
        if (startOffset != null && startOffset.compareTo(BigDecimal.ZERO) > 0) {
            mergeParams.put("start_offset", startOffset);
        }
        LlmGatewayChatResponse merge = llmGatewayClient.chat(
                tenantId, "beat-dub-merge-" + jobId,
                new LlmGatewayChatRequest(mergeModel, List.of(new LlmGatewayMessage("user", "")), mergeParams, null, null, projectId));
        if (merge == null || merge.response() == null || merge.response().isBlank()) {
            throw VideoGenException.upstream("llm-gateway returned no muxed video for job_id=" + jobId);
        }
        BigDecimal mergeCost = merge.usage() == null ? BigDecimal.ZERO : merge.usage().cost();

        return new DubResult(merge.response(), synthesisCost.add(mergeCost));
    }

    private LlmGatewayChatResponse fusedCloneAndSynthesize(String tenantId, UUID jobId, UUID projectId, String model, String referenceAudioUrl, String combinedText) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reference_audio_url", referenceAudioUrl);
        params.put("text", combinedText);
        return llmGatewayClient.chat(tenantId, "beat-dub-synthesize-" + jobId,
                new LlmGatewayChatRequest(model, List.of(new LlmGatewayMessage("user", combinedText)), params, null, null, projectId));
    }

    /** No sample to clone -- {@code voiceId} is either a prepared human clone or a stock provider
     * voice id, so this is just the TTS half of {@link #cloneThenSynthesize}, skipping the clone
     * call entirely. */
    private LlmGatewayChatResponse directTtsSynthesize(
            String tenantId, UUID jobId, UUID projectId, String voiceId, String combinedText, String resolvedTtsModel,
            SceneEnergyDirective directive, String languageCode) {
        Map<String, Object> ttsParams = new LinkedHashMap<>();
        ttsParams.put("voice_id", voiceId);
        ttsParams.putAll(directive.extraTtsParams());
        return llmGatewayClient.chat(tenantId, "beat-dub-tts-" + jobId,
                new LlmGatewayChatRequest(resolvedTtsModel, List.of(new LlmGatewayMessage("user", combinedText)), ttsParams, null, null, projectId,
                        LlmGatewayLanguageSelection.fromBcp47String(languageCode)));
    }

    /** ElevenLabs' two-call shape: clone the reference sample into a {@code voice_id} (no text --
     * a bare clone, {@code ElevenLabsProvider}'s own convention for "no text param present"),
     * then speak {@code combinedText} in that voice via a normal TTS call. Costs from both calls
     * are summed into the single {@code LlmGatewayChatResponse} the fused path would have
     * returned, so the caller doesn't need to know which path ran. */
    private LlmGatewayChatResponse cloneThenSynthesize(
            String tenantId, UUID jobId, UUID projectId, String cloneModel, String referenceAudioUrl, String combinedText,
            String resolvedTtsModel, SceneEnergyDirective directive, String languageCode) {
        Map<String, Object> cloneParams = new LinkedHashMap<>();
        cloneParams.put("reference_audio_url", referenceAudioUrl);
        LlmGatewayChatResponse clone = llmGatewayClient.chat(tenantId, "beat-dub-clone-" + jobId,
                new LlmGatewayChatRequest(cloneModel, List.of(new LlmGatewayMessage("user", referenceAudioUrl)), cloneParams, null, null, projectId));
        if (clone == null || clone.response() == null || clone.response().isBlank()) {
            throw VideoGenException.upstream("llm-gateway returned no voice_id from cloning for job_id=" + jobId);
        }
        String voiceId = clone.response();
        BigDecimal cloneCost = clone.usage() == null ? BigDecimal.ZERO : clone.usage().cost();

        Map<String, Object> ttsParams = new LinkedHashMap<>();
        ttsParams.put("voice_id", voiceId);
        ttsParams.putAll(directive.extraTtsParams());
        LlmGatewayChatResponse tts = llmGatewayClient.chat(tenantId, "beat-dub-tts-" + jobId,
                new LlmGatewayChatRequest(resolvedTtsModel, List.of(new LlmGatewayMessage("user", combinedText)), ttsParams, null, null, projectId,
                        LlmGatewayLanguageSelection.fromBcp47String(languageCode)));
        if (tts == null) {
            return null;
        }
        BigDecimal ttsCost = tts.usage() == null ? BigDecimal.ZERO : tts.usage().cost();
        return new LlmGatewayChatResponse(tts.jobId(), tts.modelId(), tts.response(),
                new LlmGatewayChatResponse.LlmGatewayUsage(0, 0, cloneCost.add(ttsCost)), tts.latencyMs());
    }

    /**
     * The beats as one string, with MiniMax's pause markers encoding the gaps between them.
     *
     * <p>Documented syntax is {@code <#x#>}, x in [0.01, 9] seconds -- a gap longer than 9s is
     * expressed as consecutive markers (9s chunks) since one marker cannot encode it directly.
     *
     * <p>The cursor advances by how long each line actually takes to say, not by the duration the
     * shot list planned for it. That distinction was the bug: the planned duration is a guess
     * written before anyone heard the line, so the moment one beat ran longer than planned, the gap
     * computed for the next beat was too long by the difference. Every later beat inherited it and
     * the error accumulated, so a shot's last line landed seconds behind the picture -- or past the
     * end of the clip, where the mux cuts it off. The gaps are also snapped to the frame grid and
     * never allowed to go negative: a negative gap emitted no marker at all, which silently pulled
     * the rest of the timeline forward instead of reporting that the beats overlap.
     *
     * @param fps the shot's frame rate, for snapping gaps to frame boundaries. Null falls back to
     *            {@link DialogueFitMath#ASSUMED_FPS}, since a gap still has to land somewhere.
     */
    private String buildPausedText(List<DialogueBeat> sorted, Integer fps) {
        int frameRate = fps == null || fps <= 0 ? DialogueFitMath.ASSUMED_FPS : fps;
        StringBuilder sb = new StringBuilder();
        double cursor = 0;
        for (DialogueBeat beat : sorted) {
            double start = beat.startSeconds() == null ? cursor : beat.startSeconds().doubleValue();
            double gap = DialogueFitMath.gapBefore(cursor, start, frameRate);
            if (gap <= 0 && start + 1e-6 < cursor) {
                log.warn("Dialogue beat starts before the previous line has finished speaking"
                                + " (start={}s, speech already at {}s) -- the gap is dropped rather than"
                                + " pulling the rest of the shot forward.",
                        String.format(Locale.ROOT, "%.2f", start), String.format(Locale.ROOT, "%.2f", cursor));
            }
            appendPause(sb, gap);
            sb.append(beat.text());
            BigDecimal spoken = beat.effectiveSeconds();
            cursor = Math.max(cursor, start) + (spoken == null ? 0 : spoken.doubleValue());
        }
        return sb.toString();
    }

    private void appendPause(StringBuilder sb, double gapSeconds) {
        double remaining = gapSeconds;
        while (remaining > 0.01) {
            double chunk = Math.min(remaining, 9.0);
            sb.append("<#").append(String.format(Locale.ROOT, "%.2f", chunk)).append("#>");
            remaining -= chunk;
        }
    }


    /**
     * Fits the synthesized dialogue inside the shot, measured rather than assumed.
     *
     * <p>The merge lays this track onto a video of fixed length and the video is the master, so an
     * audio track longer than the shot is not slightly long -- it is cut off mid-sentence in the
     * finished film, with nothing downstream to notice. That is what this prevents.
     *
     * <p>Measured with ffprobe, not estimated from the text. Characters per second is not a
     * constant: it varies by language, by voice and by how much silence the pause markers put
     * between beats, so any character budget is wrong for some project. The rendered audio is the
     * only honest source of its own duration.
     *
     * <p>Compressed with atempo, which changes pace without changing pitch, and only up to
     * {@code maxSpeedUp} -- past that speech stops being listenable and a shot that cannot be
     * spoken in its own duration is a planning problem, not something to paper over. When the
     * overrun is beyond what fitting can fix, this says so with both numbers and the duration the
     * shot would need, and still fits as far as the cap allows: a fast line beats a severed one.
     *
     * <p>Returns the original URL unchanged if it already fits, or if anything here fails -- the
     * dub is worth more than the trim.
     */
    private String fitAudioToShot(UUID jobId, String audioUrl, Integer shotDurationSeconds, Integer shotFps,
                                  double actualVideoSeconds) {
        if (audioUrl == null || shotDurationSeconds == null || shotDurationSeconds <= 0) {
            return audioUrl;
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("dub-fit-" + jobId);
            Path source = workDir.resolve("dialogue-in");
            download(audioUrl, source);

            double actualSeconds = probeDurationSeconds(source);
            if (actualSeconds <= 0) {
                return audioUrl;
            }
            // The real ceiling is the generated picture, to the frame. Falls back to the whole-second
            // figure only when the probe could not read the clip.
            double shotSeconds = actualVideoSeconds > 0 ? actualVideoSeconds : shotDurationSeconds;
            // One frame of slack, not a fixed quarter-second: a quarter of a second is six frames at
            // 24fps and twelve at 48, so a constant in seconds is a different amount of tolerance on
            // every project. A track within one frame of the shot cannot be trimmed to fit better.
            int frameRate = shotFps == null || shotFps <= 0 ? DialogueFitMath.ASSUMED_FPS : shotFps;
            if (DialogueFitMath.framesCeil(actualSeconds, frameRate) <= DialogueFitMath.framesCeil(shotSeconds, frameRate)) {
                return audioUrl;
            }

            double required = actualSeconds / shotSeconds;
            double tempo = Math.min(required, maxSpeedUp);
            if (required > maxSpeedUp) {
                log.warn("Dialogue overruns shot jobId={} audio={}s shot={}s -- needs {}s to be spoken"
                                + " at a natural pace. Fitting at the {}x cap; the shot's planned duration"
                                + " is too short for the line it carries.",
                        jobId, String.format(Locale.ROOT, "%.2f", actualSeconds), shotDurationSeconds,
                        String.format(Locale.ROOT, "%.1f", actualSeconds), maxSpeedUp);
            } else {
                log.info("Fitting dialogue to shot jobId={} audio={}s shot={}s tempo={}x",
                        jobId, String.format(Locale.ROOT, "%.2f", actualSeconds), shotDurationSeconds,
                        String.format(Locale.ROOT, "%.2f", tempo));
            }

            Path fitted = workDir.resolve("dialogue-fitted.mp3");
            runProcess(List.of("ffmpeg", "-y", "-i", source.toString(),
                    "-filter:a", atempoChain(tempo), "-vn", fitted.toString()));
            if (!Files.exists(fitted) || Files.size(fitted) == 0) {
                log.warn("Dialogue fit produced no output jobId={} -- keeping the original track", jobId);
                return audioUrl;
            }
            VideoAssetPersistenceService.PersistedAsset persisted = assetPersistenceService.uploadFile(
                    defaultDubBucket, "dub-fitted/%s.mp3".formatted(jobId), fitted);
            return assetPersistenceService.presignedUrl(persisted.bucket(), persisted.objectKey());
        } catch (Exception ex) {
            log.warn("Could not fit dialogue to shot jobId={} -- keeping the original track: {}",
                    jobId, ex.getMessage());
            return audioUrl;
        } finally {
            deleteQuietly(workDir);
        }
    }

    /** atempo takes 0.5-2.0 per instance, so a larger change is a chain of them -- 2.4x is
     * 2.0 then 1.2, not a single invalid filter. */
    private String atempoChain(double tempo) {
        StringBuilder sb = new StringBuilder();
        double remaining = tempo;
        while (remaining > 2.0) {
            sb.append("atempo=2.0,");
            remaining /= 2.0;
        }
        sb.append(String.format(Locale.ROOT, "atempo=%.4f", remaining));
        return sb.toString();
    }

    private double probeDurationSeconds(Path file) {
        try {
            Process process = new ProcessBuilder(List.of(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toString()))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return Double.parseDouble(output.lines().findFirst().orElse("-1").trim());
        } catch (Exception ex) {
            log.warn("Could not probe dialogue duration: {}", ex.getMessage());
            return -1;
        }
    }

    private void download(String url, Path target) throws Exception {
        try (var in = java.net.URI.create(url).toURL().openStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runProcess(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var in = process.getInputStream()) {
            in.readAllBytes();
        }
        if (!process.waitFor(ffmpegTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg exited " + process.exitValue());
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
                    // Temp dir; a leftover file is not worth failing a finished dub over.
                }
            });
        } catch (Exception ignored) {
            // As above.
        }
    }

    public record DubResult(String finalVideoUrl, BigDecimal cost) {
    }
}
