package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.entity.ProjectConfig;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.repository.ProjectConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reads and writes a project's generation controls (project_config, V39).
 *
 * <p>Read where each step runs, not once when a shot is prepared: switching music off in the
 * console takes effect on the next render, including one already queued. A project with no config
 * row gets {@link GenerationControlsView#DEFAULTS}.
 */
@Service
@RequiredArgsConstructor
public class GenerationControlsService {

    private final ProjectConfigRepository projectConfigRepository;

    @Transactional(readOnly = true)
    public GenerationControlsView forProject(UUID tenantId, UUID projectId) {
        return projectConfigRepository.findById(projectId)
                .filter(config -> tenantId.equals(config.getTenantId()))
                .map(GenerationControlsService::view)
                .orElse(GenerationControlsView.DEFAULTS);
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
}
