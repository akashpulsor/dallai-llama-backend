package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayLanguageSelection;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.sceneenergy.SceneEnergyDirective;
import com.dalai.llama.videogen.service.sceneenergy.SceneEnergyStrategyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
@Component
public class BeatDubbingService {

    private final LlmGatewayClient llmGatewayClient;
    private final SceneEnergyStrategyResolver sceneEnergyStrategyResolver;
    private final String voiceCloneModel;
    private final String ttsModel;
    private final String mergeModel;

    public BeatDubbingService(
            LlmGatewayClient llmGatewayClient,
            SceneEnergyStrategyResolver sceneEnergyStrategyResolver,
            @Value("${video-gen.llm-gateway.default-voice-clone-model}") String voiceCloneModel,
            @Value("${video-gen.llm-gateway.default-tts-model}") String ttsModel,
            @Value("${video-gen.llm-gateway.default-audio-video-merge-model}") String mergeModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.sceneEnergyStrategyResolver = sceneEnergyStrategyResolver;
        this.voiceCloneModel = voiceCloneModel;
        this.ttsModel = ttsModel;
        this.mergeModel = mergeModel;
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
        return dub(tenantId, jobId, projectId, beats, silentVideoUrl, null, null);
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
                          String voiceCloneModelOverride, String ttsModelOverride) {
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
                ? buildPausedText(sorted)
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

        BigDecimal startOffset = sorted.get(0).startSeconds();
        Map<String, Object> mergeParams = new LinkedHashMap<>();
        mergeParams.put("video_url", silentVideoUrl);
        mergeParams.put("audio_url", synthesis.response());
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

    /** MiniMax's documented pause-marker syntax is {@code <#x#>}, x in [0.01, 9] seconds -- a gap
     * longer than 9s is expressed as consecutive markers (9s chunks) since one marker can't encode
     * it directly. */
    private String buildPausedText(List<DialogueBeat> sorted) {
        StringBuilder sb = new StringBuilder();
        BigDecimal cursor = BigDecimal.ZERO;
        for (DialogueBeat beat : sorted) {
            appendPause(sb, beat.startSeconds().subtract(cursor));
            sb.append(beat.text());
            cursor = beat.startSeconds().add(beat.durationSeconds());
        }
        return sb.toString();
    }

    private void appendPause(StringBuilder sb, BigDecimal gapSeconds) {
        double remaining = gapSeconds.doubleValue();
        while (remaining > 0.01) {
            double chunk = Math.min(remaining, 9.0);
            sb.append("<#").append(String.format(Locale.ROOT, "%.2f", chunk)).append("#>");
            remaining -= chunk;
        }
    }

    public record DubResult(String finalVideoUrl, BigDecimal cost) {
    }
}
