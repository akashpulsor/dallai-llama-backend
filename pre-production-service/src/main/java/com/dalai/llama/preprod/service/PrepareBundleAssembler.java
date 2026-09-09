package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.CameraPlanView;
import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ContinuityBibleView;
import com.dalai.llama.preprod.dto.LightingPlanView;
import com.dalai.llama.preprod.dto.PrepareBundleView;
import com.dalai.llama.preprod.dto.ProjectConfigView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.ShotBackgroundMusicView;
import com.dalai.llama.preprod.dto.ShotBundleView;
import com.dalai.llama.preprod.dto.ShotDialogueBeatView;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotProductReferenceView;
import com.dalai.llama.preprod.dto.ShotView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles {@link PrepareBundleView} -- the fat aggregate that replaces video-gen's ~90 per-
 * project HTTP calls (6 project + 6 per-shot × N shots) with one. Per-row not-found (a shot
 * without a camera plan yet, no background music picked, etc.) resolves to null in the bundle
 * rather than failing the whole call -- same degrade-gracefully convention as the per-endpoint
 * reads.
 *
 * <p>Deliberately NOT wrapped in an outer @Transactional: each nested service call opens its
 * own transaction (its own @Transactional readOnly). If we put an outer readOnly=true here, any
 * nested call that throws {@code PreProductionException.notFound} for a genuinely-absent optional
 * (which softGet is designed to catch) marks the SHARED transaction rollback-only, and the outer
 * commit then throws {@code UnexpectedRollbackException} -- turning every "some shot has no
 * background music" into a 500 for the whole prepare-bundle call, which is exactly the
 * downstream failure this class was designed to prevent (see softGet's own comment). Skipping
 * the outer transaction gives each nested read its own tx boundary and keeps the degrade-
 * gracefully contract intact.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareBundleAssembler {

    private final ContinuityBibleService continuityBibleService;
    private final ProjectConfigService projectConfigService;
    private final CastAssignmentService castAssignmentService;
    private final CastProfileService castProfileService;
    private final ScriptGenerationService scriptGenerationService;
    private final ShotListGenerationService shotListGenerationService;
    private final ShotDialogueBeatService shotDialogueBeatService;
    private final CameraPlanService cameraPlanService;
    private final LightingPlanService lightingPlanService;
    private final ShotImageService shotImageService;
    private final ShotBackgroundMusicService shotBackgroundMusicService;
    private final ShotProductReferenceService shotProductReferenceService;


    public PrepareBundleView assemble(UUID tenantId, UUID projectId) {
        long startMs = System.currentTimeMillis();

        ContinuityBibleView bible = softGet(() -> continuityBibleService.getView(tenantId, projectId), "continuity-bible", projectId);
        ProjectConfigView config = softGet(() -> projectConfigService.get(tenantId, projectId), "project-config", projectId);
        ScriptView script = softGet(() -> scriptGenerationService.get(tenantId, projectId), "script", projectId);
        List<CastAssignmentView> castAssignments = softList(() -> castAssignmentService.list(projectId), "cast-assignments", projectId);
        List<CastProfileView> castProfiles = softList(() -> castProfileService.list(tenantId, projectId, null), "cast-profiles", projectId);
        List<ShotView> shots = softList(() -> shotListGenerationService.list(tenantId, projectId), "shots", projectId);

        if (shots.isEmpty()) {
            log.warn("prepare-bundle: no shots found for tenantId={} projectId={}", tenantId, projectId);
            return new PrepareBundleView(bible,config,script,castAssignments,castProfiles,List.of());
        }
        List<UUID> shotIds = shots.stream().map(ShotView::id).toList();

        Map<UUID, List<ShotDialogueBeatView>> beatsByShot = shotDialogueBeatService.listByShotIds(tenantId, shotIds);
        Map<UUID, CameraPlanView> cameraPlansByShot = cameraPlanService.listByShotIds(tenantId, shotIds);
        Map<UUID, LightingPlanView> lightingPlansByShot = lightingPlanService.listByShotIds(tenantId, shotIds);
        Map<UUID, List<ShotImageView>> imagesByShot = shotImageService.listByShotIds(tenantId, shotIds);
        Map<UUID, ShotBackgroundMusicView> musicByShot = shotBackgroundMusicService.listByShotIds(tenantId, shotIds);
        Map<UUID, ShotProductReferenceView> productRefsByShot = shotProductReferenceService.listByShotIds(tenantId, shotIds);

        List<ShotBundleView> shotBundles = new ArrayList<>(shots.size());
        for (ShotView shot : shots) {
            UUID shotId = shot.id();

            shotBundles.add(new ShotBundleView(
                    shot,
                    beatsByShot.getOrDefault(shotId, List.of()),
                    cameraPlansByShot.get(shotId),
                    lightingPlansByShot.get(shotId),
                    imagesByShot.getOrDefault(shotId, List.of()),
                    musicByShot.get(shotId),
                    productRefsByShot.get(shotId)
            ));
        }

        log.info("prepare-bundle assembled projectId={} shots={} castAssignments={} castProfiles={} bibleLocks={} scriptCharacters={} elapsedMs={}",
                projectId, shots.size(), castAssignments.size(), castProfiles.size(),
                bible == null || bible.locks() == null ? 0 : bible.locks().size(),
                script == null || script.characters() == null ? 0 : script.characters().size(),
                System.currentTimeMillis() - startMs);

        return new PrepareBundleView(bible, config, script, castAssignments, castProfiles, shotBundles);
    }
    private ShotBundleView assembleShot(UUID tenantId, ShotView shot) {
        UUID shotId = shot.id();
        List<ShotDialogueBeatView> beats = softList(() -> shotDialogueBeatService.list(tenantId, shotId), "dialogue-beats", shotId);
        CameraPlanView cameraPlan = softGet(() -> cameraPlanService.get(tenantId, shotId), "camera-plan", shotId);
        LightingPlanView lightingPlan = softGet(() -> lightingPlanService.get(tenantId, shotId), "lighting-plan", shotId);
        List<ShotImageView> shotImages = softList(() -> shotImageService.list(tenantId, shotId), "shot-images", shotId);
        ShotBackgroundMusicView backgroundMusic = softGet(() -> shotBackgroundMusicService.get(tenantId, shotId), "background-music", shotId);
        ShotProductReferenceView productReference = softGet(() -> shotProductReferenceService.get(tenantId, shotId), "product-reference", shotId);
        return new ShotBundleView(shot, beats, cameraPlan, lightingPlan, shotImages, backgroundMusic, productReference);
    }


    /** A single row's not-found (or any per-service failure) → null in the bundle, not a 500 for
     * the whole prepare. Same shape as the per-endpoint reads that would 404 individually. */
    private <T> T softGet(java.util.function.Supplier<T> supplier, String kind, Object id) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            log.debug("prepare-bundle: {} missing for id={} -- {}", kind, id, ex.getMessage());
            return null;
        }
    }

    private <T> List<T> softList(java.util.function.Supplier<List<T>> supplier, String kind, Object id) {
        try {
            List<T> value = supplier.get();
            return value == null ? List.of() : value;
        } catch (RuntimeException ex) {
            log.debug("prepare-bundle: {} missing for id={} -- {}", kind, id, ex.getMessage());
            return List.of();
        }
    }
}
