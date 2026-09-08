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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@Service
public class TestVoiceService {

    private static final String DEFAULT_TEST_LINE =
            "This is a short sample of my voice for this character.";

    private final PreProductionServiceClient preProductionServiceClient;
    private final LlmGatewayClient llmGatewayClient;
    private final MinioClient publicMinioClient;
    private final String voiceCloneModel;
    private final String ttsModel;

    public TestVoiceService(
            PreProductionServiceClient preProductionServiceClient,
            LlmGatewayClient llmGatewayClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${video-gen.llm-gateway.default-voice-clone-model}") String voiceCloneModel,
            @Value("${video-gen.llm-gateway.default-tts-model}") String ttsModel
    ) {
        this.preProductionServiceClient = preProductionServiceClient;
        this.llmGatewayClient = llmGatewayClient;
        this.publicMinioClient = publicMinioClient;
        this.voiceCloneModel = voiceCloneModel;
        this.ttsModel = ttsModel;
    }

    public TestVoiceResult testVoice(UUID tenantId, UUID projectId, UUID shotId, String text) {
        PreProductionViews.PrepareBundleView bundle = preProductionServiceClient.getPrepareBundle(tenantId, projectId)
                .orElseThrow(() -> VideoGenException.badRequest("No prepare-bundle for project " + projectId));
        PreProductionViews.ShotBundleView shotBundle = bundle.shots().stream()
                .filter(s -> shotId.equals(s.shot().id()))
                .findFirst()
                .orElseThrow(() -> VideoGenException.badRequest("Shot " + shotId + " is not in project " + projectId));
        PreProductionViews.ShotView shot = shotBundle.shot();
        String characterKey = shot.primaryCharacterKey();
        if (characterKey == null || characterKey.isBlank()) {
            throw VideoGenException.badRequest("Shot " + shot.shotRef() + " has no primary character to voice");
        }
        PreProductionViews.CastProfileView profile = resolveCastProfile(bundle, characterKey);
        if (profile == null) {
            throw VideoGenException.badRequest("No cast profile assigned to character " + characterKey);
        }

        String line = text == null || text.isBlank() ? defaultLineFor(shot) : text.trim();
        String languageCode = bundle.projectConfig() == null ? null : bundle.projectConfig().dialogueLanguage();

        String voiceId;
        String mode;
        if (profile.voiceRefBucket() != null && profile.voiceRefObjectKey() != null) {
            // Cast likeness -- clone from the uploaded sample, then TTS.
            String signedSampleUrl = presign(profile.voiceRefBucket(), profile.voiceRefObjectKey());
            voiceId = cloneReference(tenantId, projectId, signedSampleUrl);
            mode = "cloned";
        } else if (profile.builtinVoiceId() != null && !profile.builtinVoiceId().isBlank()) {
            // AI-generated identity -- direct TTS with the built-in voice.
            voiceId = profile.builtinVoiceId();
            mode = "built_in";
        } else {
            throw VideoGenException.badRequest("Cast profile " + profile.id() + " has neither an uploaded voice sample nor a built-in voice set");
        }

        String audioDataUri = synthesize(tenantId, projectId, voiceId, line, languageCode);
        return new TestVoiceResult(mode, voiceId, audioDataUri);
    }

    /** Same fallback ladder DialogueBeatsEditor's frontend uses today: prefer the shot's voice-
     * over line, then the script line if it's genuinely dialogue (not scene direction), then a
     * neutral sentence so the preview still plays for a non-dialogue shot. */
    private String defaultLineFor(PreProductionViews.ShotView shot) {
        if (shot.voiceOver() != null && !shot.voiceOver().isBlank()) return shot.voiceOver().trim();
        if ("DIALOGUE".equals(shot.shotType()) && shot.scriptLine() != null && !shot.scriptLine().isBlank()) {
            return shot.scriptLine().trim();
        }
        return DEFAULT_TEST_LINE;
    }

    private PreProductionViews.CastProfileView resolveCastProfile(
            PreProductionViews.PrepareBundleView bundle, String characterKey) {
        if (bundle.script() == null) return null;
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
            throw VideoGenException.upstream("Could not presign voice sample URL: " + ex.getMessage(), ex);
        }
    }

    private String cloneReference(UUID tenantId, UUID projectId, String signedSampleUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reference_audio_url", signedSampleUrl);
        LlmGatewayChatResponse clone = llmGatewayClient.chat(tenantId.toString(),
                "test-voice-clone-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(voiceCloneModel, List.of(new LlmGatewayMessage("user", signedSampleUrl)),
                        params, null, null, projectId));
        if (clone == null || clone.response() == null || clone.response().isBlank()) {
            throw VideoGenException.upstream("llm-gateway returned no cloned voice_id");
        }
        return clone.response();
    }

    private String synthesize(UUID tenantId, UUID projectId, String voiceId, String text, String languageCode) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("voice_id", voiceId);
        if (languageCode != null && !languageCode.isBlank()) {
            params.put("language_code", languageCode);
        }
        LlmGatewayChatResponse tts = llmGatewayClient.chat(tenantId.toString(),
                "test-voice-tts-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(ttsModel, List.of(new LlmGatewayMessage("user", text)),
                        params, null, null, projectId));
        if (tts == null || tts.response() == null || tts.response().isBlank()) {
            throw VideoGenException.upstream("llm-gateway returned no synthesized audio");
        }
        return tts.response();
    }

    public record TestVoiceResult(String mode, String providerVoiceId, String audioDataUri) {
    }
}
