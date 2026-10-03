package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.service.lifecycle.ProjectStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectClientLockStampTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService service = new ProjectService(projects, mock(ProjectConfigRepository.class), new ProjectStateMachine());

    @Test
    void aPublishedProjectLocksAndIsStamped() {
        Project project = project(ProjectStatus.READY_FOR_REVIEW, null);

        service.stampClientLocked(tenantId, projectId);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.CLIENT_LOCKED);
        assertThat(project.getClientLockedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = ProjectStatus.class, names = {"DRAFT", "SCRIPT_READY", "SCREENPLAY_READY"})
    void aPaidLockIsRecordedEvenWhenTheStageCannotMoveToLocked(ProjectStatus stage) {
        Project project = project(stage, null);

        service.stampClientLocked(tenantId, projectId);

        assertThat(project.getClientLockedAt()).isNotNull();
        assertThat(project.getStatus()).isEqualTo(stage);
    }

    @Test
    void aRepeatLockKeepsTheOriginalStamp() {
        OffsetDateTime first = OffsetDateTime.now().minusDays(1);
        Project project = project(ProjectStatus.CLIENT_LOCKED, first);

        service.stampClientLocked(tenantId, projectId);

        assertThat(project.getClientLockedAt()).isEqualTo(first);
    }

    @Test
    void publishingReachesReviewAndReviewReachesLocked() {
        ProjectStateMachine machine = new ProjectStateMachine();
        assertThat(machine.canTransition(ProjectStatus.VIDEO_GENERATION_COMPLETE, ProjectStatus.READY_FOR_REVIEW)).isTrue();
        assertThat(machine.canTransition(ProjectStatus.READY_FOR_REVIEW, ProjectStatus.CLIENT_LOCKED)).isTrue();
    }

    private Project project(ProjectStatus status, OffsetDateTime clientLockedAt) {
        Project project = new Project();
        project.setId(projectId);
        project.setTenantId(tenantId);
        project.setStatus(status);
        project.setClientLockedAt(clientLockedAt);
        when(projects.findByIdAndTenantId(projectId, tenantId)).thenReturn(Optional.of(project));
        return project;
    }
}
