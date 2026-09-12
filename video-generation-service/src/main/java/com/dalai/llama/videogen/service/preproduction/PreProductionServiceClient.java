package com.dalai.llama.videogen.service.preproduction;

import com.dalai.llama.videogen.service.VideoGenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * video-generation-service's client back to pre-production-service, reading the material the
 * prepare-scene assembler needs (continuity bible, project config, cast, shot list, dialogue
 * beats, camera/lighting plans, shot images, background music, product references).
 *
 * <p>Every call targets pre-production-service's {@code /api/v1/internal/tenants/{tenantId}/**}
 * aliases ({@link com.dalai.llama.preprod.controller.InternalShotAssemblyController} -- see its
 * class comment for why the JWT-authenticated /v1/** endpoints can't be called directly from
 * here). Blocking on purpose, wrapped in {@link VideoGenException#upstream}; every method
 * returns null / empty on 404 rather than throwing, so a shot with a missing optional source
 * (e.g. no background music yet) degrades gracefully at the assembly layer instead of failing
 * the whole prepare call.
 */
@Component
public class PreProductionServiceClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public PreProductionServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${video-gen.pre-production.base-url}") String baseUrl,
            @Value("${video-gen.pre-production.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    // --- One-shot aggregate (preferred entry point for prepare) ---

    /** Single call that returns everything a project prepare needs -- replaces ~90 per-shot
     * HTTP calls (bible/config/cast/script + 6 per-shot × N shots) with one round trip. The
     * per-endpoint methods below still exist for callers that only need one slice of the data. */
    public Optional<PreProductionViews.PrepareBundleView> getPrepareBundle(UUID tenantId, UUID projectId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/prepare-bundle",
                new ParameterizedTypeReference<PreProductionViews.PrepareBundleView>() {}, tenantId, projectId);
    }

    /** Claims a cast profile's provider clone identity without replacing a previously stored one. */
    public PreProductionViews.ClonedVoiceIdentityView persistClonedVoiceIfAbsent(
            UUID tenantId, UUID projectId, UUID castProfileId, String clonedVoiceId, String providerId, String voiceIdentityType) {
        try {
            return webClient.put()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/cast-profiles/{castProfileId}/cloned-voice",
                            tenantId, projectId, castProfileId)
                    .bodyValue(new PreProductionViews.PersistClonedVoiceRequest(clonedVoiceId, providerId, voiceIdentityType))
                    .retrieve()
                    .bodyToMono(PreProductionViews.ClonedVoiceIdentityView.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw VideoGenException.upstream(
                    "pre-production-service persist cloned voice failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        } catch (RuntimeException ex) {
            throw VideoGenException.upstream("pre-production-service persist cloned voice failed: " + ex.getMessage(), ex);
        }
    }

    // --- Project-scoped reads ---

    public Optional<PreProductionViews.ContinuityBibleView> getContinuityBible(UUID tenantId, UUID projectId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/continuity-bible",
                new ParameterizedTypeReference<PreProductionViews.ContinuityBibleView>() {}, tenantId, projectId);
    }

    public Optional<PreProductionViews.ProjectConfigView> getProjectConfig(UUID tenantId, UUID projectId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/config",
                new ParameterizedTypeReference<PreProductionViews.ProjectConfigView>() {}, tenantId, projectId);
    }

    public List<PreProductionViews.CastAssignmentView> listCastAssignments(UUID tenantId, UUID projectId) {
        return getList("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/cast-assignments",
                new ParameterizedTypeReference<List<PreProductionViews.CastAssignmentView>>() {}, tenantId, projectId);
    }

    public List<PreProductionViews.CastProfileView> listCastProfiles(UUID tenantId, UUID projectId) {
        return getList("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/cast-profiles",
                new ParameterizedTypeReference<List<PreProductionViews.CastProfileView>>() {}, tenantId, projectId);
    }

    public List<PreProductionViews.ShotView> listShots(UUID tenantId, UUID projectId) {
        return getList("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots",
                new ParameterizedTypeReference<List<PreProductionViews.ShotView>>() {}, tenantId, projectId);
    }

    /** Includes the ScriptCharacterView list needed to resolve a dialogue beat's characterKey
     * (String) to a scriptCharacterId (UUID) for cast-assignment lookup. */
    public Optional<PreProductionViews.ScriptView> getScript(UUID tenantId, UUID projectId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/script",
                new ParameterizedTypeReference<PreProductionViews.ScriptView>() {}, tenantId, projectId);
    }

    // --- Per-shot reads ---

    public List<PreProductionViews.ShotDialogueBeatView> listDialogueBeats(UUID tenantId, UUID shotId) {
        return getList("/api/v1/internal/tenants/{tenantId}/shots/{shotId}/dialogue-beats",
                new ParameterizedTypeReference<List<PreProductionViews.ShotDialogueBeatView>>() {}, tenantId, shotId);
    }

    public Optional<PreProductionViews.CameraPlanView> getCameraPlan(UUID tenantId, UUID shotId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/shots/{shotId}/camera-plan",
                new ParameterizedTypeReference<PreProductionViews.CameraPlanView>() {}, tenantId, shotId);
    }

    public Optional<PreProductionViews.LightingPlanView> getLightingPlan(UUID tenantId, UUID shotId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/shots/{shotId}/lighting-plan",
                new ParameterizedTypeReference<PreProductionViews.LightingPlanView>() {}, tenantId, shotId);
    }

    public List<PreProductionViews.ShotImageView> listShotImages(UUID tenantId, UUID shotId) {
        return getList("/api/v1/internal/tenants/{tenantId}/shots/{shotId}/images",
                new ParameterizedTypeReference<List<PreProductionViews.ShotImageView>>() {}, tenantId, shotId);
    }

    public Optional<PreProductionViews.ShotBackgroundMusicView> getBackgroundMusic(UUID tenantId, UUID shotId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/shots/{shotId}/background-music",
                new ParameterizedTypeReference<PreProductionViews.ShotBackgroundMusicView>() {}, tenantId, shotId);
    }

    public Optional<PreProductionViews.ShotProductReferenceView> getProductReference(UUID tenantId, UUID shotId) {
        return getOptional("/api/v1/internal/tenants/{tenantId}/shots/{shotId}/product-reference",
                new ParameterizedTypeReference<PreProductionViews.ShotProductReferenceView>() {}, tenantId, shotId);
    }

    // --- shared plumbing ---

    private <T> Optional<T> getOptional(String uriTemplate, ParameterizedTypeReference<T> type, Object... uriVars) {
        try {
            T body = webClient.get()
                    .uri(uriTemplate, uriVars)
                    .retrieve()
                    .bodyToMono(type)
                    .block(Duration.ofMillis(timeoutMs));
            return Optional.ofNullable(body);
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        } catch (WebClientResponseException ex) {
            throw VideoGenException.upstream(
                    "pre-production-service GET %s failed status=%s body=%s".formatted(uriTemplate, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        } catch (RuntimeException ex) {
            throw VideoGenException.upstream(
                    "pre-production-service unreachable for GET %s: %s".formatted(uriTemplate, ex.getMessage()), ex);
        }
    }

    private <T> List<T> getList(String uriTemplate, ParameterizedTypeReference<List<T>> type, Object... uriVars) {
        try {
            List<T> body = webClient.get()
                    .uri(uriTemplate, uriVars)
                    .retrieve()
                    .bodyToMono(type)
                    .block(Duration.ofMillis(timeoutMs));
            return body == null ? List.of() : body;
        } catch (WebClientResponseException.NotFound ex) {
            return List.of();
        } catch (WebClientResponseException ex) {
            throw VideoGenException.upstream(
                    "pre-production-service GET %s failed status=%s body=%s".formatted(uriTemplate, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        } catch (RuntimeException ex) {
            throw VideoGenException.upstream(
                    "pre-production-service unreachable for GET %s: %s".formatted(uriTemplate, ex.getMessage()), ex);
        }
    }
}
