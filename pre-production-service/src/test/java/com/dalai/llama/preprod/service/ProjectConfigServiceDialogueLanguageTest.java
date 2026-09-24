package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.ProjectConfig;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guards {@link ProjectConfigService#resolveDialogueLanguage} -- the helper both {@code
 * ScriptGenerationService} and {@code ScreenplayGenerationService} call so a language chosen at
 * generate time overrides the saved default AND persists to {@code project_config} for every
 * downstream stage. Reproduces the "Devanagari script even though the creator wanted a different
 * language" incident: the pre-fix screenplay path never read the config at all and the script
 * path had no way to override at generate time. */
class ProjectConfigServiceDialogueLanguageTest {

    private final ProjectConfigRepository repo = mock(ProjectConfigRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectConfigService service = new ProjectConfigService(repo, projectService);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @Test
    void requestedLanguagePersistsAndWinsOverSavedDefault() {
        ProjectConfig existing = ProjectConfig.builder()
                .projectId(projectId).dialogueLanguage("hi-Latn-IN").build();
        when(repo.findByProjectId(projectId)).thenReturn(Optional.of(existing));
        when(projectService.requireProject(tenantId, projectId)).thenReturn(mock(Project.class));
        when(repo.save(any(ProjectConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        String resolved = service.resolveDialogueLanguage(tenantId, projectId, "en-US");

        assertThat(resolved).isEqualTo("en-US");
        ArgumentCaptor<ProjectConfig> saved = ArgumentCaptor.forClass(ProjectConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getDialogueLanguage()).isEqualTo("en-US");
    }

    @Test
    void blankRequestedLanguageFallsBackToSavedConfig() {
        ProjectConfig existing = ProjectConfig.builder()
                .projectId(projectId).dialogueLanguage("hi-Latn-IN").build();
        when(repo.findByProjectId(projectId)).thenReturn(Optional.of(existing));

        String resolved = service.resolveDialogueLanguage(tenantId, projectId, "  ");

        assertThat(resolved).isEqualTo("hi-Latn-IN");
        verify(repo, never()).save(any());
    }

    @Test
    void nullRequestedLanguageFallsBackToSavedConfig() {
        ProjectConfig existing = ProjectConfig.builder()
                .projectId(projectId).dialogueLanguage("bn-IN").build();
        when(repo.findByProjectId(projectId)).thenReturn(Optional.of(existing));

        String resolved = service.resolveDialogueLanguage(tenantId, projectId, null);

        assertThat(resolved).isEqualTo("bn-IN");
        verify(repo, never()).save(any());
    }

    @Test
    void fallsBackToEnUsWhenNothingSavedAndNothingRequested() {
        when(repo.findByProjectId(projectId)).thenReturn(Optional.empty());

        String resolved = service.resolveDialogueLanguage(tenantId, projectId, null);

        assertThat(resolved).isEqualTo("en-US");
        verify(repo, never()).save(any());
    }

    @Test
    void fallsBackToEnUsWhenSavedIsBlank() {
        ProjectConfig existing = ProjectConfig.builder()
                .projectId(projectId).dialogueLanguage(" ").build();
        when(repo.findByProjectId(projectId)).thenReturn(Optional.of(existing));

        String resolved = service.resolveDialogueLanguage(tenantId, projectId, null);

        assertThat(resolved).isEqualTo("en-US");
        verify(repo, times(0)).save(any());
    }
}
