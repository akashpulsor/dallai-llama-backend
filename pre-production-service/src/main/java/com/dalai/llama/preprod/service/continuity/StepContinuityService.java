package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotContinuityOverride;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.domain.entity.StepContinuityAnalysisRecord;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ShotContinuityOverrideRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.repository.StepContinuityAnalysisRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.PromptInput;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a step shot's visual continuity: gathers the shot's prompt inputs and narrative, asks the
 * analysis model what the reference image establishes and how each input relates to it (one vision
 * call, cached per step until any input or the reference image changes), then applies the user's
 * choices and the authority order in {@link ContinuityResolver}.
 */
@Service
public class StepContinuityService {

    static final String TASK_KEY = "PRE_PROD_STEP_CONTINUITY";

    private final StepContinuityAnalysisRepository analysisRepository;
    private final ShotContinuityOverrideRepository overrideRepository;
    private final ShotRepository shotRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public StepContinuityService(
            StepContinuityAnalysisRepository analysisRepository,
            ShotContinuityOverrideRepository overrideRepository,
            ShotRepository shotRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String model
    ) {
        this.analysisRepository = analysisRepository;
        this.overrideRepository = overrideRepository;
        this.shotRepository = shotRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.llmGatewayClient = llmGatewayClient;
        // A model naming a field or input that doesn't exist is dropped, not a parse failure.
        this.objectMapper = objectMapper.copy().enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        this.model = model;
    }

    /** The step's resolution. An analysis that fails leaves the step with the plain step instruction
     * and a warning -- generation is never blocked on continuity. */
    @Transactional
    public ContinuityResolution resolve(UUID tenantId, Shot target, Shot source, ShotImage sourceImage, String sourceImageUri,
                                        ShotImageKind kind, PromptInput.Context context) {
        Map<PromptInput, String> inputs = PromptInput.rawValues(context);
        ContinuityResolver.Evidence evidence = evidence(tenantId, target, source);
        Map<String, String> variables = variables(target, source, inputs, evidence);
        Map<VisualField, String> userOverrides = overrideRepository.findByShotId(target.getId()).stream()
                .collect(Collectors.toMap(ShotContinuityOverride::getField, ShotContinuityOverride::getValue, (a, b) -> b,
                        () -> new EnumMap<>(VisualField.class)));
        try {
            ContinuityAnalysis analysis = analysis(tenantId, target, source, sourceImage, sourceImageUri, kind, variables);
            return ContinuityResolver.resolve(analysis, inputs, userOverrides, evidence);
        } catch (RuntimeException ex) {
            ContinuityResolution fallback = ContinuityResolver.resolve(ContinuityAnalysis.EMPTY, inputs, userOverrides, evidence);
            List<String> warnings = new java.util.ArrayList<>(fallback.warnings());
            warnings.add("Continuity check unavailable (" + ex.getMessage() + ") -- the previous shot's look is asked for in general terms only.");
            return new ContinuityResolution(fallback.resolvedVisualState(), fallback.overrides(), List.copyOf(warnings),
                    fallback.changesForThisShot(), fallback.newElementLighting(), fallback.substitutions());
        }
    }

    @Transactional
    public void setOverride(UUID tenantId, UUID shotId, VisualField field, String value) {
        requireShot(tenantId, shotId);
        if (value == null || value.isBlank()) {
            throw PreProductionException.badRequest("A value is required to override " + field.label().toLowerCase());
        }
        OffsetDateTime now = OffsetDateTime.now();
        ShotContinuityOverride row = overrideRepository.findByShotIdAndField(shotId, field)
                .orElseGet(() -> ShotContinuityOverride.builder().tenantId(tenantId).shotId(shotId).field(field).createdAt(now).build());
        row.setValue(value.trim());
        row.setUpdatedAt(now);
        overrideRepository.save(row);
    }

    @Transactional
    public void clearOverride(UUID tenantId, UUID shotId, VisualField field) {
        requireShot(tenantId, shotId);
        overrideRepository.findByShotIdAndField(shotId, field).ifPresent(overrideRepository::delete);
    }

    private ContinuityAnalysis analysis(UUID tenantId, Shot target, Shot source, ShotImage sourceImage, String sourceImageUri,
                                        ShotImageKind kind, Map<String, String> variables) {
        String hash = sha256(sourceImage.getBucket() + "/" + sourceImage.getObjectKey() + "\n" + variables);
        StepContinuityAnalysisRecord cached = analysisRepository
                .findByShotIdAndSourceShotIdAndKind(target.getId(), source.getId(), kind).orElse(null);
        if (cached != null && hash.equals(cached.getInputHash())) {
            return parse(cached.getAnalysisJson());
        }
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "step-continuity-" + target.getId() + "-" + hash.substring(0, 16),
                new LlmGatewayChatRequest(model, List.of(new LlmGatewayMessage("user", "", List.of(sourceImageUri))),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY, variables).withProjectId(target.getProjectId()));
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("the continuity analysis returned nothing");
        }
        String json = JsonExtraction.stripCodeFence(response.response());
        ContinuityAnalysis analysis = parse(json);
        StepContinuityAnalysisRecord record = cached != null ? cached : StepContinuityAnalysisRecord.builder()
                .tenantId(tenantId).shotId(target.getId()).sourceShotId(source.getId()).kind(kind).build();
        record.setInputHash(hash);
        record.setAnalysisJson(json);
        record.setCreatedAt(OffsetDateTime.now());
        analysisRepository.save(record);
        return analysis;
    }

    ContinuityAnalysis parse(String json) {
        try {
            return objectMapper.readValue(json, ContinuityAnalysis.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("could not read the continuity analysis: " + ex.getMessage());
        }
    }

    /** The shot's own words always count as evidence; the screenplay scene's words only when this shot
     * opens a different scene from the reference shot's -- within one scene nothing has transitioned. */
    private ContinuityResolver.Evidence evidence(UUID tenantId, Shot target, Shot source) {
        String shotText = join(" ", target.getAction(), target.getScriptLine());
        if (target.getScreenplaySceneId() == null || Objects.equals(target.getScreenplaySceneId(), source.getScreenplaySceneId())) {
            return new ContinuityResolver.Evidence(shotText, "");
        }
        String sceneText = screenplaySceneRepository.findByIdAndTenantId(target.getScreenplaySceneId(), tenantId)
                .map(StepContinuityService::sceneText).orElse("");
        return new ContinuityResolver.Evidence(shotText, sceneText);
    }

    private static String sceneText(ScreenplayScene scene) {
        return join(" ", scene.getSlug(), scene.getSummary());
    }

    /** Everything the analysis template is given besides the image. Its {@link #toString} feeds the
     * cache hash, so any change to an input re-runs the analysis. */
    private Map<String, String> variables(Shot target, Shot source, Map<PromptInput, String> inputs, ContinuityResolver.Evidence evidence) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("fields", Arrays.stream(VisualField.values()).map(field -> field.name() + " (" + field.label() + ")")
                .collect(Collectors.joining(", ")));
        variables.put("previousShot", orNone(source.getAction()));
        variables.put("shotDescription", orNone(evidence.shotDescription()));
        variables.put("screenplay", evidence.screenplay().isBlank()
                ? "(this shot continues the previous shot's scene -- no scene change)" : evidence.screenplay());
        variables.put("inputs", inputs.isEmpty() ? "(none)" : inputs.entrySet().stream()
                .map(entry -> entry.getKey().name() + " [" + entry.getKey().source() + ", " + entry.getKey().label() + "]: " + entry.getValue())
                .collect(Collectors.joining("\n")));
        return variables;
    }

    private void requireShot(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
    }

    private static String join(String separator, String... parts) {
        return Arrays.stream(parts).filter(part -> !PromptInput.isEmpty(part)).map(String::trim).collect(Collectors.joining(separator));
    }

    private static String orNone(String text) {
        return PromptInput.isEmpty(text) ? "(none)" : text.trim();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
