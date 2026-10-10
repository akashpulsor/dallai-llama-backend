package com.dalai.llama.preprod.service.showcase;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.domain.ReviewActor;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.repository.ClientReviewSessionRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ReviewCommentRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.postproduction.PostProductionFilmClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShowcaseSourceServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final PostProductionFilmClient films = mock(PostProductionFilmClient.class);
    private final ShotImageRepository shotImages = mock(ShotImageRepository.class);
    private final ClientReviewSessionRepository sessions = mock(ClientReviewSessionRepository.class);
    private final ReviewCommentRepository comments = mock(ReviewCommentRepository.class);
    private final CreativeDirectionRepository directions = mock(CreativeDirectionRepository.class);
    private final ShowcaseSourceService service =
            new ShowcaseSourceService(projects, films, shotImages, sessions, comments, directions);

    @Test
    void reportsTheFilmTheLockTheConsentAndTheEvidence() {
        UUID tenant = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        OffsetDateTime locked = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        Project project = new Project();
        project.setId(projectId);
        project.setTenantId(tenant);
        project.setName("Masala chai launch");
        project.setStatus(ProjectStatus.CLIENT_LOCKED);
        project.setClientLockedAt(locked);
        project.setMarketingTermsVersion("2026-10");
        project.setMarketingTermsAcceptedAt(locked);
        when(projects.findByIdAndTenantId(projectId, tenant)).thenReturn(Optional.of(project));
        when(films.getPublishedFilm(tenant, projectId)).thenReturn(Optional.of(new PostProductionFilmClient.PublishedFilm(
                true, UUID.randomUUID(), "https://minio/film.mp4?sig", new BigDecimal("45.20"), 1080, 1920,
                locked, locked.minusHours(1))));
        when(shotImages.countForProject(projectId)).thenReturn(12L);
        when(sessions.countByProjectId(projectId)).thenReturn(2L);
        when(comments.countByProjectId(projectId)).thenReturn(5L);
        when(directions.countByProjectIdAndApprovedVia(projectId, ReviewActor.CLIENT)).thenReturn(1L);

        ShowcaseSourceService.ShowcaseSourceView view = service.source(tenant, projectId);

        assertThat(view.filmReady()).isTrue();
        assertThat(view.width()).isEqualTo(1080);
        assertThat(view.height()).isEqualTo(1920);
        assertThat(view.durationSeconds()).isEqualByComparingTo("45.20");
        assertThat(view.renderedAt()).isEqualTo(locked.minusHours(1));
        assertThat(view.clientLockedAt()).isEqualTo(locked);
        assertThat(view.marketingTermsVersion()).isEqualTo("2026-10");
        assertThat(view.shotImages()).isEqualTo(12);
        assertThat(view.clientReviewSessions()).isEqualTo(2);
        assertThat(view.clientComments()).isEqualTo(5);
        assertThat(view.clientApprovals()).isEqualTo(1);
        assertThat(view.downloadUrl()).isEqualTo("https://minio/film.mp4?sig");
    }

    @Test
    void noPublishedFilmMeansNotReady_andAnotherTenantsProjectIs404() {
        UUID tenant = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        project.setStatus(ProjectStatus.IN_PRODUCTION);
        when(projects.findByIdAndTenantId(projectId, tenant)).thenReturn(Optional.of(project));
        when(films.getPublishedFilm(tenant, projectId)).thenReturn(Optional.empty());

        assertThat(service.source(tenant, projectId).filmReady()).isFalse();
        assertThatThrownBy(() -> service.source(UUID.randomUUID(), projectId)).isInstanceOf(PreProductionException.class);
    }
}
