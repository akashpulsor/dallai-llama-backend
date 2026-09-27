package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.MusicPlanRecord;
import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.repository.MusicPlanRepository;
import com.dalai.llama.preprod.service.MediaAssetService;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Renders a project's planned score into one continuous audio file.
 *
 * <p>Separate from {@link MusicDirectorPlannerService} on purpose: planning is cheap and
 * re-runnable, this is billable. Keeping them apart is what lets a creator iterate on the plan
 * and the prompt without paying for audio each time, and is why planning never generates as a
 * side effect.
 *
 * <p>Provider-neutral. It names a configured model id and passes a prompt and a duration in
 * SECONDS; whether that resolves to ElevenLabs Music or fal.ai ACE-Step, and what unit each of
 * those wants, is decided in llm-gateway and translated inside the provider adapter. Nothing here
 * knows a provider URL, key or request shape.
 */
@Service
public class ProjectScoreGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ProjectScoreGenerationService.class);

    private final MusicPlanRepository musicPlanRepository;
    private final MusicDirectorPlannerService plannerService;
    private final MediaAssetService mediaAssetService;
    private final LlmGatewayClient llmGatewayClient;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final String bucket;
    private final String prefix;
    private final String musicModel;

    public ProjectScoreGenerationService(
            MusicPlanRepository musicPlanRepository,
            MusicDirectorPlannerService plannerService,
            MediaAssetService mediaAssetService,
            LlmGatewayClient llmGatewayClient,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.background-music-prefix:project-score}") String prefix,
            @Value("${pre-production.llm-gateway.background-music-model}") String musicModel
    ) {
        this.musicPlanRepository = musicPlanRepository;
        this.plannerService = plannerService;
        this.mediaAssetService = mediaAssetService;
        this.llmGatewayClient = llmGatewayClient;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.bucket = bucket;
        this.prefix = prefix;
        this.musicModel = musicModel;
    }

    /**
     * Generates the whole-video score from the stored plan.
     *
     * <p>{@code modelOverride} lets a caller pick a different registered music model without a
     * config change -- the same escape hatch post-production's music generation already has. It
     * is a model id, not a provider name: provider resolution stays in llm-gateway.
     *
     * <p>The requested length is the plan's real duration in seconds. Both unit keys are sent
     * because the two music providers read different ones (ElevenLabs Music wants
     * {@code music_length_ms}, fal ACE-Step wants {@code duration} in seconds) and sending one
     * would silently give the other its default length. The adapters own the translation; this
     * only states the same fact twice rather than deciding for them.
     */
    @Transactional
    public ScoreView generate(UUID tenantId, UUID projectId, String modelOverride) {
        MusicPlanRecord record = plannerService.require(tenantId, projectId);
        MusicPlan plan = plannerService.toPlan(record);
        double seconds = plan.totalDurationSeconds() == null ? 0 : plan.totalDurationSeconds();
        if (seconds <= 0) {
            throw PreProductionException.badRequest("Music plan has no duration to generate against");
        }

        String modelId = (modelOverride == null || modelOverride.isBlank()) ? musicModel : modelOverride.trim();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("prompt", record.getMasterPrompt());
        params.put("music_length_ms", (int) Math.round(seconds * 1000));
        params.put("duration", seconds);

        long startedAt = System.currentTimeMillis();
        // Prompt + duration + model in the key. A stable per-project key would replay the first
        // score forever, which is exactly the trap the per-shot music generation fell into --
        // editing the prompt has to produce different audio.
        String idempotencyKey = "project-score-" + projectId + "-"
                + Integer.toHexString((record.getMasterPrompt() + "|" + seconds + "|" + modelId).hashCode());

        LlmGatewayChatResponse response = llmGatewayClient.chat(tenantId.toString(), idempotencyKey,
                new LlmGatewayChatRequest(modelId,
                        List.of(new LlmGatewayMessage("user", record.getMasterPrompt())),
                        params, null, null).withProjectId(projectId));

        byte[] audio = decodeAudio(response, modelId, projectId);
        String objectKey = "%s/%s/%s.mp3".formatted(prefix, projectId, UUID.randomUUID());
        upload(objectKey, audio);

        record.setBucket(bucket);
        record.setObjectKey(objectKey);
        record.setGeneratedModelId(modelId);
        record.setUpdatedAt(OffsetDateTime.now());
        musicPlanRepository.save(record);
        mediaAssetService.registerIfAbsent(tenantId, bucket, objectKey, MediaAssetType.SHOT_BACKGROUND_MUSIC);

        log.info("Generated project score projectId={} model={} durationSeconds={} bytes={} latencyMs={}",
                projectId, modelId, seconds, audio.length, System.currentTimeMillis() - startedAt);
        return new ScoreView(signedUrl(bucket, objectKey), seconds, modelId);
    }

    @Transactional(readOnly = true)
    public ScoreView get(UUID tenantId, UUID projectId) {
        MusicPlanRecord record = plannerService.require(tenantId, projectId);
        if (record.getObjectKey() == null) {
            throw PreProductionException.notFound("Project " + projectId + " has a plan but no generated score yet");
        }
        return new ScoreView(signedUrl(record.getBucket(), record.getObjectKey()),
                record.getTotalDurationSeconds().doubleValue(), record.getGeneratedModelId());
    }

    /**
     * Music providers answer in one of two shapes: a {@code data:audio} URI (ElevenLabs Music
     * returns raw bytes, which llm-gateway base64-encodes) or an https URL to a produced file
     * (fal.ai returns {@code audio.url}). Both are normalised to bytes here so storage and every
     * downstream consumer see one thing regardless of who generated it.
     */
    private byte[] decodeAudio(LlmGatewayChatResponse response, String modelId, UUID projectId) {
        String content = response == null ? null : response.response();
        if (content == null || content.isBlank()) {
            throw PreProductionException.upstream(
                    "llm-gateway returned no score audio for project " + projectId + " (model=" + modelId + ")");
        }
        if (content.startsWith("data:")) {
            int comma = content.indexOf(',');
            return Base64.getDecoder().decode(comma < 0 ? content : content.substring(comma + 1));
        }
        if (content.startsWith("http://") || content.startsWith("https://")) {
            return download(content, modelId, projectId);
        }
        throw PreProductionException.upstream(
                "llm-gateway returned an unrecognised score payload for project " + projectId + " (model=" + modelId + ")");
    }

    private byte[] download(String url, String modelId, UUID projectId) {
        try (var stream = java.net.URI.create(url).toURL().openStream()) {
            return stream.readAllBytes();
        } catch (Exception ex) {
            throw PreProductionException.upstream(
                    "Could not download the generated score for project " + projectId
                            + " (model=" + modelId + "): " + ex.getMessage());
        }
    }

    private void upload(String objectKey, byte[] bytes) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("audio/mpeg")
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload the project score to MinIO: " + ex.getMessage());
        }
    }

    private String signedUrl(String objectBucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(objectBucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            return null;
        }
    }

    /** What a generated score looks like to a caller -- no provider detail beyond which model
     * produced it, which is kept because "why does this project sound different" is otherwise
     * unanswerable once the configured model moves on. */
    public record ScoreView(String signedUrl, Double durationSeconds, String modelId) {}
}
