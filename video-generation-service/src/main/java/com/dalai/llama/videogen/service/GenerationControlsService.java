package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.entity.ProjectConfig;
import com.dalai.llama.videogen.domain.entity.ShotGenerationControls;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.generationplan.ShotGenerationControlsView;
import com.dalai.llama.videogen.repository.ProjectConfigRepository;
import com.dalai.llama.videogen.repository.ShotGenerationControlsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A shot's generation controls: its own set when it has one (shot_generation_controls, V41), else
 * its project's defaults (project_config, V40), else {@link GenerationControlsView#DEFAULTS}.
 *
 * <p>Read where each step runs, not once when a shot is prepared: switching music off takes effect
 * on the next render, including one already queued.
 */
@Service
@RequiredArgsConstructor
public class GenerationControlsService {

    private final ProjectConfigRepository projectConfigRepository;
    private final ShotGenerationControlsRepository shotControlsRepository;

    /** The controls a step of this shot's render follows. {@code shotId} null: the project's. */
    @Transactional(readOnly = true)
    public GenerationControlsView forShot(UUID tenantId, UUID projectId, UUID shotId) {
        return shotView(tenantId, projectId, shotId).controls();
    }

    @Transactional(readOnly = true)
    public ShotGenerationControlsView shotView(UUID tenantId, UUID projectId, UUID shotId) {
        if (shotId != null) {
            var own = shotControlsRepository.findByShotIdAndTenantId(shotId, tenantId);
            if (own.isPresent()) {
                return new ShotGenerationControlsView(view(own.get()), true);
            }
        }
        return new ShotGenerationControlsView(forProject(tenantId, projectId), false);
    }

    /** The project's defaults, for every shot without its own set. */
    @Transactional(readOnly = true)
    public GenerationControlsView forProject(UUID tenantId, UUID projectId) {
        return projectConfigRepository.findById(projectId)
                .filter(config -> tenantId.equals(config.getTenantId()))
                .map(GenerationControlsService::view)
                .orElse(GenerationControlsView.DEFAULTS);
    }

    @Transactional
    public ShotGenerationControlsView updateShot(UUID tenantId, UUID projectId, UUID shotId, GenerationControlsView controls) {
        ShotGenerationControls row = shotControlsRepository.findByShotIdAndTenantId(shotId, tenantId)
                .orElseGet(() -> ShotGenerationControls.builder().shotId(shotId).tenantId(tenantId).projectId(projectId).build());
        row.setFitDurationToDialogue(controls.fitDurationToDialogue());
        row.setAutoDubDialogue(controls.autoDubDialogue());
        row.setMixBackgroundMusic(controls.mixBackgroundMusic());
        row.setPreventDuplicateRenders(controls.preventDuplicateRenders());
        row.setAttachPreviousLastFrame(controls.attachPreviousLastFrame());
        row.setConformToPlannedDuration(controls.conformToPlannedDuration());
        row.setInterpolateWhenSlowing(controls.interpolateWhenSlowing());
        row.setUpdatedAt(OffsetDateTime.now());
        return new ShotGenerationControlsView(view(shotControlsRepository.save(row)), true);
    }

    /** Back to the project's defaults. */
    @Transactional
    public ShotGenerationControlsView resetShot(UUID tenantId, UUID projectId, UUID shotId) {
        shotControlsRepository.findByShotIdAndTenantId(shotId, tenantId).ifPresent(shotControlsRepository::delete);
        return new ShotGenerationControlsView(forProject(tenantId, projectId), false);
    }

    @Transactional
    public GenerationControlsView update(UUID tenantId, UUID projectId, GenerationControlsView controls) {
        ProjectConfig config = projectConfigRepository.findById(projectId)
                .filter(row -> tenantId.equals(row.getTenantId()))
                .orElseGet(() -> ProjectConfig.builder()
                        .projectId(projectId)
                        .tenantId(tenantId)
                        .defaultDialogueFlag(FlagState.ON)
                        .defaultCaptionsFlag(FlagState.OFF)
                        .autoApprove(false)
                        .build());
        config.setFitDurationToDialogue(controls.fitDurationToDialogue());
        config.setAutoDubDialogue(controls.autoDubDialogue());
        config.setMixBackgroundMusic(controls.mixBackgroundMusic());
        config.setPreventDuplicateRenders(controls.preventDuplicateRenders());
        config.setAttachPreviousLastFrame(controls.attachPreviousLastFrame());
        config.setConformToPlannedDuration(controls.conformToPlannedDuration());
        config.setInterpolateWhenSlowing(controls.interpolateWhenSlowing());
        config.setUpdatedAt(OffsetDateTime.now());
        return view(projectConfigRepository.save(config));
    }

    private static GenerationControlsView view(ProjectConfig config) {
        return new GenerationControlsView(
                Boolean.TRUE.equals(config.getFitDurationToDialogue()),
                !Boolean.FALSE.equals(config.getAutoDubDialogue()),
                !Boolean.FALSE.equals(config.getMixBackgroundMusic()),
                !Boolean.FALSE.equals(config.getPreventDuplicateRenders()),
                Boolean.TRUE.equals(config.getAttachPreviousLastFrame()),
                !Boolean.FALSE.equals(config.getConformToPlannedDuration()),
                !Boolean.FALSE.equals(config.getInterpolateWhenSlowing()));
    }

    private static GenerationControlsView view(ShotGenerationControls row) {
        return new GenerationControlsView(row.isFitDurationToDialogue(), row.isAutoDubDialogue(), row.isMixBackgroundMusic(),
                row.isPreventDuplicateRenders(), row.isAttachPreviousLastFrame(), row.isConformToPlannedDuration(),
                row.isInterpolateWhenSlowing());
    }
}
