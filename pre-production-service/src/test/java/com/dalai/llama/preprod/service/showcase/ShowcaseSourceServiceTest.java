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
    private final com.dalai.llama.preprod.repository.ShotRepository shots = mock(com.dalai.llama.preprod.repository.ShotRepository.class);
    private final io.minio.MinioClient minio = mock(io.minio.MinioClient.class);
    private final ShowcaseSourceService service =
            new ShowcaseSourceService(projects, films, shotImages, sessions, comments, directions, shots, minio);

    @Test
    void thePosterIsTheFirstShotsFinishedFrame_signed() throws Exception {
        UUID projectId = UUID.randomUUID();
        com.dalai.llama.preprod.domain.entity.Shot first = new com.dalai.llama.preprod.domain.entity.Shot();
        first.setId(UUID.randomUUID());
        com.dalai.llama.preprod.domain.entity.Shot second = new com.dalai.llama.preprod.domain.entity.Shot();
        second.setId(UUID.randomUUID());
        when(shots.findByProjectIdOrderByShotNumberAsc(projectId)).thenReturn(java.util.List.of(first, second));
        when(shotImages.findByShotId(first.getId())).thenReturn(java.util.List.of(
                image(com.dalai.llama.preprod.domain.ShotImageKind.STORYBOARD, "sketch.png"),
                image(com.dalai.llama.preprod.domain.ShotImageKind.PRODUCTION, "frame.png")));
        when(minio.getPresignedObjectUrl(org.mockito.ArgumentMatchers.argThat(a -> "frame.png".equals(a.object()))))
                .thenReturn("https://minio/frame.png?sig");

        assertThat(service.posterUrl(projectId)).isEqualTo("https://minio/frame.png?sig");
        assertThat(service.posterUrl(UUID.randomUUID())).isNull();
    }

    @Test
    void theCreatorChoosesTheThumbnailFromTheFilmsFrames_andAFrameFromElsewhereIsRefused() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        com.dalai.llama.preprod.domain.entity.Project project = new com.dalai.llama.preprod.domain.entity.Project();
        project.setId(projectId);
        when(projects.findByIdAndTenantId(projectId, tenant)).thenReturn(java.util.Optional.of(project));
        when(projects.findById(projectId)).thenReturn(java.util.Optional.of(project));
        com.dalai.llama.preprod.domain.entity.Shot first = new com.dalai.llama.preprod.domain.entity.Shot();
        first.setId(UUID.randomUUID());
        first.setShotNumber(1);
        com.dalai.llama.preprod.domain.entity.Shot third = new com.dalai.llama.preprod.domain.entity.Shot();
        third.setId(UUID.randomUUID());
        third.setShotNumber(3);
        when(shots.findByProjectIdOrderByShotNumberAsc(projectId)).thenReturn(java.util.List.of(first, third));
        var opening = image(com.dalai.llama.preprod.domain.ShotImageKind.PRODUCTION, "opening.png");
        opening.setId(UUID.randomUUID());
        opening.setShotId(first.getId());
        var hero = image(com.dalai.llama.preprod.domain.ShotImageKind.PRODUCTION, "hero.png");
        hero.setId(UUID.randomUUID());
        hero.setShotId(third.getId());
        when(shotImages.findByShotId(first.getId())).thenReturn(java.util.List.of(opening));
        when(shotImages.findByShotId(third.getId())).thenReturn(java.util.List.of(hero));
        when(minio.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                "https://minio/" + call.<io.minio.GetPresignedObjectUrlArgs>getArgument(0).object() + "?sig");

        // Until one is chosen, the first shot's frame is the thumbnail.
        assertThat(service.posterFrameViews(tenant, projectId)).extracting(ShowcaseSourceService.PosterFrameView::chosen)
                .containsExactly(true, false);

        var after = service.choosePoster(tenant, projectId, hero.getId());

        assertThat(after).extracting(ShowcaseSourceService.PosterFrameView::chosen).containsExactly(false, true);
        assertThat(after.get(1).shotNumber()).isEqualTo(3);
        assertThat(service.posterUrl(projectId)).isEqualTo("https://minio/hero.png?sig");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.choosePoster(tenant, projectId, UUID.randomUUID()))
                .hasMessageContaining("isn't one of this film's shots");
    }

    private static com.dalai.llama.preprod.domain.entity.ShotImage image(com.dalai.llama.preprod.domain.ShotImageKind kind, String key) {
        com.dalai.llama.preprod.domain.entity.ShotImage i = new com.dalai.llama.preprod.domain.entity.ShotImage();
        i.setKind(kind);
        i.setBucket("shots");
        i.setObjectKey(key);
        return i;
    }

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
                locked, locked.minusHours(1), "https://minio/film-stamped.mp4?sig")));
        when(shotImages.countForProject(projectId)).thenReturn(12L);
        when(sessions.countByProjectId(projectId)).thenReturn(2L);
        when(comments.countByProjectId(projectId)).thenReturn(5L);
        when(directions.countByProjectIdAndApprovedVia(projectId, ReviewActor.CLIENT)).thenReturn(1L);

        ShowcaseSourceService.ShowcaseSourceView view = service.source(tenant, projectId);

        assertThat(view.filmReady()).isTrue();
        assertThat(view.stampedDownloadUrl()).isEqualTo("https://minio/film-stamped.mp4?sig");
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
    void listsOnlyProjectsWithAFinishedFilm() {
        UUID tenant = UUID.randomUUID();
        Project done = new Project();
        done.setId(UUID.randomUUID());
        done.setName("Done");
        Project wip = new Project();
        wip.setId(UUID.randomUUID());
        wip.setName("Still making");
        OffsetDateTime at = OffsetDateTime.parse("2026-10-01T10:00:00Z");
        when(projects.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(java.util.List.of(done, wip));
        when(films.getPublishedFilm(tenant, done.getId())).thenReturn(Optional.of(new PostProductionFilmClient.PublishedFilm(
                true, UUID.randomUUID(), "https://minio/film.mp4?sig", new BigDecimal("30"), 1920, 1080, at, at, null)));
        when(films.getPublishedFilm(tenant, wip.getId())).thenReturn(Optional.empty());

        assertThat(service.films(tenant)).singleElement().satisfies(f -> {
            assertThat(f.projectId()).isEqualTo(done.getId());
            assertThat(f.width()).isEqualTo(1920);
        });
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
