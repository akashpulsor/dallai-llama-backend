package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.entity.ProjectConfig;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.repository.ProjectConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The console's switches: defaults that let a shot be generated, and nothing reset behind them. */
class GenerationControlsServiceTest {

    private final ProjectConfigRepository repository = mock(ProjectConfigRepository.class);
    private final GenerationControlsService service = new GenerationControlsService(repository);
    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    @Test
    void aProjectWithNoConfigGetsThePathThatAlwaysGenerates() {
        when(repository.findById(project)).thenReturn(Optional.empty());

        GenerationControlsView controls = service.forProject(tenant, project);

        assertThat(controls).isEqualTo(GenerationControlsView.DEFAULTS);
        assertThat(controls.fitDurationToDialogue()).isFalse();
        assertThat(controls.attachPreviousLastFrame()).isFalse();
    }

    @Test
    void savingTheSwitchesKeepsEverythingElseOnTheRow() {
        ProjectConfig row = ProjectConfig.builder().projectId(project).tenantId(tenant)
                .defaultDialogueFlag(FlagState.ON).defaultCaptionsFlag(FlagState.ON).autoApprove(true)
                .preferredVoiceCloneModel("fal-ai/minimax/voice-clone").build();
        when(repository.findById(project)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        GenerationControlsView saved = service.update(tenant, project, new GenerationControlsView(true, false, false, false, true));

        assertThat(saved).isEqualTo(new GenerationControlsView(true, false, false, false, true));
        assertThat(row.getPreferredVoiceCloneModel()).isEqualTo("fal-ai/minimax/voice-clone");
        assertThat(row.getAutoApprove()).isTrue();
    }

    @Test
    void savingTheFeatureFlagDefaultsNoLongerResetsTheSwitchesOrTheVoiceModel() {
        ProjectConfig row = ProjectConfig.builder().projectId(project).tenantId(tenant)
                .defaultDialogueFlag(FlagState.ON).defaultCaptionsFlag(FlagState.OFF).autoApprove(false)
                .preferredVoiceCloneModel("fal-ai/minimax/voice-clone").attachPreviousLastFrame(true).build();
        when(repository.findById(project)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        new DefaultProjectConfigService(repository).updateDefaults(tenant, project,
                new FeatureFlags(FlagState.OFF, FlagState.ON), true);

        assertThat(row.getDefaultCaptionsFlag()).isEqualTo(FlagState.ON);
        assertThat(row.getAttachPreviousLastFrame()).isTrue();
        assertThat(row.getPreferredVoiceCloneModel()).isEqualTo("fal-ai/minimax/voice-clone");
    }
}
