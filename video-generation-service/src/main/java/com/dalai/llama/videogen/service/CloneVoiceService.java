package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Test-voice preview for the video-generation page. Given a shot + a bit of text, resolves the
 * shot's primary character to its cast profile and returns a short audio sample rendered with
 * the character's real voice identity -- exactly the same clone-if-needed + TTS path {@link
 * BeatDubbingService#dub} runs at approve() time, just against a caller-supplied line instead of
 * a persisted DialogueBeat.
 *
 * <p>Two flows, matching per-character identity state exactly:
 * <ul>
 *   <li><b>Cast likeness</b> (profile has {@code voiceRefBucket/ObjectKey}) -- MinIO-presign the
 *       actor sample, clone it via {@code elevenlabs/instant-voice-clone}, then TTS with the
 *       returned voice id. Sample-preview URL is signed fresh per call so it stays short-lived --
 *       llm-gateway fetches it once during the clone call and no persistent handle is needed.</li>
 *   <li><b>AI-generated identity</b> ({@code builtinVoiceId} set, no upload) -- direct TTS with
 *       the built-in voice id; no clone call.</li>
 * </ul>
 *
 * <p>Deliberately lives here, not in pre-production-service: pre-prod owns planning artifacts
 * (cast, script, beats), video-generation-service owns actual voice/video production. The user
 * on the video page is doing production work; the reachable button belongs to whichever service
 * runs the flow.
 */

@Slf4j
@Service
public class CloneVoiceService {

    private static final String DEFAULT_TEST_LINE = "This is a short sample of my voice for this character.";

    private final PreProductionServiceClient preProductionServiceClient;
    private final LlmGatewayClient llmGatewayClient;
    private final MinioClient publicMinioClient;
    private final String voiceCloneModel;
    private final String ttsModel;

    public CloneVoiceService(PreProductionServiceClient preProductionServiceClient, LlmGatewayClient llmGatewayClient,
                             @Qualifier("publicMinioClient") MinioClient publicMinioClient,
                             @Value("${video-gen.llm-gateway.default-voice-clone-model}") String voiceCloneModel,
                             @Value("${video-gen.llm-gateway.default-tts-model}") String ttsModel) {
        this.preProductionServiceClient = preProductionServiceClient;
        this.llmGatewayClient = llmGatewayClient;
        this.publicMinioClient = publicMinioClient;
        this.voiceCloneModel = voiceCloneModel;
        this.ttsModel = ttsModel;
    }

    public CloneVoiceResult cloneVoice(UUID tenantId, UUID projectId, UUID shotId, String text) {
        long startMs = System.currentTimeMillis();

        log.info("clone-voice started projectId={} shotId={} customText={}", projectId, shotId, text != null && !text.isBlank());

        PreProductionViews.PrepareBundleView bundle = preProductionServiceClient.getPrepareBundle(tenantId, projectId)
                .orElseThrow(() -> VideoGenException.badRequest("No prepare-bundle for project " + projectId));

        PreProductionViews.ShotBundleView shotBundle = bundle.shots().stream()
                .filter(s -> shotId.equals(s.shot().id()))
                .findFirst()
                .orElseThrow(() -> VideoGenException.badRequest("Shot " + shotId + " is not in project " + projectId));

        CloneVoiceResult result = cloneVoice(tenantId, projectId, shotBundle, text, bundle);

        log.info("clone-voice completed projectId={} shotId={} mode={} elapsedMs={}", projectId, shotId, result.mode(), System.currentTimeMillis() - startMs);

        return result;
    }

    public List<CloneVoiceResult> cloneProject(UUID tenantId, UUID projectId) {
        long startMs = System.currentTimeMillis();

        log.info("clone-project started projectId={}", projectId);

        PreProductionViews.PrepareBundleView bundle = preProductionServiceClient.getPrepareBundle(tenantId, projectId)
                .orElseThrow(() -> VideoGenException.badRequest("No prepare-bundle for project " + projectId));

        List<CloneVoiceResult> results = new ArrayList<>();
        int totalBeats = 0;
        int skippedBeats = 0;

        if (bundle.shots() == null || bundle.shots().isEmpty()) {
            log.warn("clone-project no shots found projectId={}", projectId);
            return results;
        }

        log.info("clone-project bundle loaded projectId={} shots={}", projectId, bundle.shots().size());

        for (PreProductionViews.ShotBundleView shotBundle : bundle.shots()) {
            PreProductionViews.ShotView shot = shotBundle.shot();

            if (shotBundle.dialogueBeats() == null || shotBundle.dialogueBeats().isEmpty()) {
                log.debug("clone-project skipping shot with no dialogue beats projectId={} shotId={} shotRef={}", projectId, shot.id(), shot.shotRef());
                continue;
            }

            for (PreProductionViews.ShotDialogueBeatView beat : shotBundle.dialogueBeats()) {
                totalBeats++;

                if (beat.text() == null || beat.text().isBlank()) {
                    skippedBeats++;
                    log.debug("clone-project skipping blank dialogue beat projectId={} shotId={} beatId={}", projectId, shot.id(), beat.id());
                    continue;
                }

                log.debug("clone-project processing beat projectId={} shotId={} beatId={}", projectId, shot.id(), beat.id());

                CloneVoiceResult result = cloneVoice(tenantId, projectId, shotBundle, beat.text(), bundle);
                results.add(result);

                log.debug("clone-project completed beat projectId={} shotId={} beatId={} mode={}", projectId, shot.id(), beat.id(), result.mode());
            }
        }

        log.info("clone-project completed projectId={} totalBeats={} generated={} skipped={} elapsedMs={}",
                projectId, totalBeats, results.size(), skippedBeats, System.currentTimeMillis() - startMs);

        return results;
    }

    private CloneVoiceResult cloneVoice(UUID tenantId, UUID projectId, PreProductionViews.ShotBundleView shotBundle, String text, PreProductionViews.PrepareBundleView bundle) {
        PreProductionViews.ShotView shot = shotBundle.shot();
        String characterKey = shot.primaryCharacterKey();

        if (characterKey == null || characterKey.isBlank()) {
            log.warn("clone-voice missing primary character projectId={} shotId={} shotRef={}", projectId, shot.id(), shot.shotRef());
            throw VideoGenException.badRequest("Shot " + shot.shotRef() + " has no primary character to voice");
        }

        PreProductionViews.CastProfileView profile = resolveCastProfile(bundle, characterKey);

        if (profile == null) {
            log.warn("clone-voice cast profile missing projectId={} shotId={} characterKey={}", projectId, shot.id(), characterKey);
            throw VideoGenException.badRequest("No cast profile assigned to character " + characterKey);
        }

        String line = text == null || text.isBlank() ? defaultLineFor(shot) : text.trim();
        String languageCode = bundle.projectConfig() == null ? null : bundle.projectConfig().dialogueLanguage();

        String voiceId;
        String mode;

        if (profile.voiceRefBucket() != null && profile.voiceRefObjectKey() != null) {
            log.debug("clone-voice uploaded reference projectId={} shotId={} castProfileId={}", projectId, shot.id(), profile.id());

            String signedSampleUrl = presign(profile.voiceRefBucket(), profile.voiceRefObjectKey());
            String cloneKey = idempotencyKey("voice-clone", profile.id(), profile.voiceRefBucket(), profile.voiceRefObjectKey(), voiceCloneModel);

            voiceId = cloneReference(tenantId, projectId, signedSampleUrl, cloneKey);
            mode = "cloned";

            // TODO: Persist cloned provider voiceId + clone modelId on CastProfile and reuse it directly.
        } else if (profile.builtinVoiceId() != null && !profile.builtinVoiceId().isBlank()) {
            voiceId = profile.builtinVoiceId();
            mode = "built_in";

            log.debug("clone-voice built-in voice projectId={} shotId={} castProfileId={}", projectId, shot.id(), profile.id());
        } else {
            log.warn("clone-voice no usable voice projectId={} shotId={} castProfileId={}", projectId, shot.id(), profile.id());
            throw VideoGenException.badRequest("Cast profile " + profile.id() + " has neither an uploaded voice sample nor a built-in voice set");
        }

        String ttsKey = idempotencyKey("voice-tts", shot.id(), voiceId, line, languageCode, ttsModel);

        log.debug("clone-voice synthesizing projectId={} shotId={} mode={} language={} textLength={}", projectId, shot.id(), mode, languageCode, line.length());

        String audioDataUri = synthesize(tenantId, projectId, voiceId, line, languageCode, ttsKey);

        return new CloneVoiceResult(mode, voiceId, audioDataUri);
    }

    private String defaultLineFor(PreProductionViews.ShotView shot) {
        if (shot.voiceOver() != null && !shot.voiceOver().isBlank()) return shot.voiceOver().trim();

        if ("DIALOGUE".equals(shot.shotType()) && shot.scriptLine() != null && !shot.scriptLine().isBlank()) {
            return shot.scriptLine().trim();
        }

        return DEFAULT_TEST_LINE;
    }

    private PreProductionViews.CastProfileView resolveCastProfile(PreProductionViews.PrepareBundleView bundle, String characterKey) {
        if (bundle.script() == null || bundle.script().characters() == null) return null;

        UUID scriptCharacterId = bundle.script().characters().stream()
                .filter(c -> characterKey.equals(c.characterKey()))
                .map(PreProductionViews.ScriptCharacterView::id)
                .findFirst()
                .orElse(null);

        if (scriptCharacterId == null) return null;

        UUID castProfileId = bundle.castAssignments().stream()
                .filter(a -> scriptCharacterId.equals(a.scriptCharacterId()))
                .map(PreProductionViews.CastAssignmentView::castProfileId)
                .findFirst()
                .orElse(null);

        if (castProfileId == null) return null;

        return bundle.castProfiles().stream()
                .filter(p -> castProfileId.equals(p.id()))
                .findFirst()
                .orElse(null);
    }

    private String presign(String bucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(15, TimeUnit.MINUTES)
                    .build());
        } catch (Exception ex) {
            log.error("clone-voice presign failed bucket={} objectKey={}", bucket, objectKey, ex);
            throw VideoGenException.upstream("Could not presign voice sample URL: " + ex.getMessage(), ex);
        }
    }

    private String cloneReference(UUID tenantId, UUID projectId, String signedSampleUrl, String idempotencyKey) {
        long startMs = System.currentTimeMillis();

        log.debug("voice-clone request projectId={} model={} idempotencyKey={}", projectId, voiceCloneModel, idempotencyKey);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reference_audio_url", signedSampleUrl);

        LlmGatewayChatResponse clone = llmGatewayClient.chat(tenantId.toString(), idempotencyKey,
                new LlmGatewayChatRequest(voiceCloneModel, List.of(new LlmGatewayMessage("user", signedSampleUrl)), params, null, null, projectId));

        if (clone == null || clone.response() == null || clone.response().isBlank()) {
            log.error("voice-clone empty response projectId={} model={} idempotencyKey={}", projectId, voiceCloneModel, idempotencyKey);
            throw VideoGenException.upstream("llm-gateway returned no cloned voice_id");
        }

        log.debug("voice-clone completed projectId={} model={} elapsedMs={}", projectId, voiceCloneModel, System.currentTimeMillis() - startMs);

        return clone.response();
    }

    private String synthesize(UUID tenantId, UUID projectId, String voiceId, String text, String languageCode, String idempotencyKey) {
        long startMs = System.currentTimeMillis();

        log.debug("voice-tts request projectId={} model={} language={} textLength={} idempotencyKey={}",
                projectId, ttsModel, languageCode, text.length(), idempotencyKey);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("voice_id", voiceId);

        if (languageCode != null && !languageCode.isBlank()) {
            params.put("language_code", languageCode);
        }

        LlmGatewayChatResponse tts = llmGatewayClient.chat(tenantId.toString(), idempotencyKey,
                new LlmGatewayChatRequest(ttsModel, List.of(new LlmGatewayMessage("user", text)), params, null, null, projectId));

        if (tts == null || tts.response() == null || tts.response().isBlank()) {
            log.error("voice-tts empty response projectId={} model={} idempotencyKey={}", projectId, ttsModel, idempotencyKey);
            throw VideoGenException.upstream("llm-gateway returned no synthesized audio");
        }

        log.debug("voice-tts completed projectId={} model={} elapsedMs={}", projectId, ttsModel, System.currentTimeMillis() - startMs);

        return tts.response();
    }

    private String idempotencyKey(String operation, UUID resourceId, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder input = new StringBuilder(operation).append(':').append(resourceId);

            for (String value : values) {
                input.append(':').append(value == null ? "" : value);
            }

            String hash = HexFormat.of().formatHex(digest.digest(input.toString().getBytes(StandardCharsets.UTF_8)));
            return operation + ":" + resourceId + ":" + hash.substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record CloneVoiceResult(String mode, String providerVoiceId, String audioDataUri) {}
}