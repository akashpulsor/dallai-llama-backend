package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.repository.ProjectConfigRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.service.lifecycle.ProjectStateMachine;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A creator can record that their client agreed to marketing use; the client's own consent is never replaced. */
class CreatorMarketingConsentTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectService service = new ProjectService(projects, mock(ProjectConfigRepository.class), mock(ProjectStateMachine.class));
    private final UUID tenant = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private Project project(OffsetDateTime acceptedAt, String version) {
        Project p = Project.builder().id(projectId).tenantId(tenant).status(ProjectStatus.SHOT_LIST_READY)
                .marketingTermsAcceptedAt(acceptedAt).marketingTermsVersion(version)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
        when(projects.findByIdAndTenantId(projectId, tenant)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void aFilmWithoutConsentIsMarkedAsCreatorAttested() {
        Project p = project(null, null);

        service.recordCreatorAttestedConsent(tenant, projectId);

        assertThat(p.getMarketingTermsVersion()).isEqualTo(ProjectService.CREATOR_ATTESTED);
        assertThat(p.getMarketingTermsAcceptedAt()).isNotNull();
        verify(projects).save(p);
    }

    @Test
    void theClientsOwnConsentAtLockIsKept() {
        OffsetDateTime atLock = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        Project p = project(atLock, "2026-10");

        service.recordCreatorAttestedConsent(tenant, projectId);

        assertThat(p.getMarketingTermsVersion()).isEqualTo("2026-10");
        assertThat(p.getMarketingTermsAcceptedAt()).isEqualTo(atLock);
        verify(projects, never()).save(any());
    }
}
