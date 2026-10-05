package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.dto.AdminProjectView;
import com.dalai.llama.preprod.repository.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AdminProjectServiceTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final AdminProjectService service = new AdminProjectService(projectRepository);

    @Test
    void namesEveryProjectOfTheTenantNewestFirst() {
        UUID tenantId = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.parse("2026-10-05T11:00:00Z");
        Project tirth = new Project();
        tirth.setId(UUID.randomUUID());
        tirth.setName("OMJI — Stay Inside the Tirth");
        tirth.setStatus(ProjectStatus.SHOT_LIST_READY);
        tirth.setCreatedAt(created);
        given(projectRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).willReturn(List.of(tirth));

        assertThat(service.list(tenantId)).containsExactly(
                new AdminProjectView(tirth.getId(), "OMJI — Stay Inside the Tirth", "SHOT_LIST_READY", created));
    }
}
