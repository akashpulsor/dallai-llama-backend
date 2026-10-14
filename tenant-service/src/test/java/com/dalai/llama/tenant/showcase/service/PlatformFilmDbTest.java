package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PickVideoRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.LinkPlatformFilmRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.OfficialUploadRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.YouTubeKitView;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.showcase.repository.OfficialUploadJobRepository;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient.VideoInfo;
import com.dalai.llama.tenant.youtube.client.YouTubeUploadClient;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.service.VideoEligibility;
import com.dalai.llama.tenant.youtube.service.YouTubeVideoCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Publishing films made on Dalaillama: the upload kit, linking a pasted video, and uploads to
 * Dalaillama's own channel through the worker, on a real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "video-host.official-channel.enabled=true", "video-host.official-channel.client-id=c",
        "video-host.official-channel.client-secret=s", "video-host.official-channel.refresh-token=r"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PlatformPublishService.class, OfficialUploadWorker.class, FundingVerifier.class, ShowcasePickService.class,
        PublicShowcaseService.class, PublicIdGenerator.class, CreatorProfileService.class, HandlePolicy.class,
        VideoEligibility.class, YouTubeVideoCache.class, OfficialChannelAccess.class, AutoOfficialUploadJob.class,
        com.dalai.llama.tenant.youtube.publish.YouTubeQuotaService.class, PlatformFilmDbTest.TestConfig.class})
class PlatformFilmDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final OffsetDateTime RENDERED = OffsetDateTime.parse("2026-10-05T10:00:00Z");
    private static final String CHANNEL = "UCriya";

    @TestConfiguration
    @EnableConfigurationProperties({ShowcaseProperties.class, YouTubeProperties.class, VideoHostProperties.class,
            com.dalai.llama.tenant.showcase.config.RankingProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private PreProductionShowcaseClient preProduction;
    @MockBean private YouTubeDataClient youTube;
    @MockBean private YouTubeUploadClient uploadClient;
    @Autowired private PlatformPublishService publishService;
    @Autowired private OfficialUploadWorker worker;
    @Autowired private ShowcasePickService pickService;
    @Autowired private PublicShowcaseService publicService;
    @Autowired private CreatorProfileService profileService;
    @Autowired private OfficialUploadJobRepository jobs;
    @Autowired private AutoOfficialUploadJob autoQueue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository items;

    private UUID creator;
    private final UUID project = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        wipe(jdbc);
        creator = tenant(jdbc, "Riya Motion");
        profileService.ensureProfile(creator, "Riya Motion");
        verifiedChannel(jdbc, creator, CHANNEL, NOW);
    }

    @Test
    void kitCarriesTheMarkerAndSaysWhetherOurChannelIsAvailable() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));

        YouTubeKitView kit = publishService.kit(creator, project);

        assertThat(kit.description()).startsWith("Made on Dalaillama · https://dalaillama.in/c/riya-motion");
        assertThat(kit.title()).isEqualTo("Made by Riya Motion on Dalaillama");
        assertThat(kit.downloadUrl()).isEqualTo("https://minio/film.mp4?sig");
        assertThat(kit.disclosureStep()).contains("Altered content");
        assertThat(kit.verifiedFunded()).isTrue();
        assertThat(kit.officialUploadAvailable()).isTrue();

        when(preProduction.source(creator, project)).thenReturn(source(true, false));
        assertThat(publishService.kit(creator, project).officialUploadBlockedReason()).contains("marketing use");
    }

    @Test
    void aMatchingLinkBecomesAPlatformItemWithItsFundingAndConsent() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(youTube.videos(List.of("filmLink001"))).thenReturn(List.of(
                video("filmLink001", CHANNEL, RENDERED.plusDays(1), 45.8, "Made on Dalaillama · https://dalaillama.in/c/riya-motion")));

        MyShowcaseItemView item = publishService.link(creator, project, link("https://youtu.be/filmLink001"));

        assertThat(item.origin()).isEqualTo(ShowcaseOrigin.PLATFORM);
        assertThat(jdbc.queryForObject("SELECT platform_proof FROM showcase_item WHERE public_id = ?", String.class, item.publicId()))
                .isEqualTo("LINK_MATCH");
        assertThat(jdbc.queryForObject("SELECT funded_verified_at IS NOT NULL AND marketing_consent_at IS NOT NULL AND client_locked_at IS NOT NULL"
                + " FROM showcase_item WHERE public_id = ?", Boolean.class, item.publicId())).isTrue();

        assertThatThrownBy(() -> publishService.link(creator, project, link("https://youtu.be/filmLink001")))
                .hasMessageContaining("already on your profile");
    }

    @Test
    void eachMatchRuleRefusesWithItsReason() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        String marker = "Made on Dalaillama";
        when(youTube.videos(List.of("otherChan01"))).thenReturn(List.of(video("otherChan01", "UCsomeone", RENDERED.plusDays(1), 45, marker)));
        when(youTube.videos(List.of("tooEarly001"))).thenReturn(List.of(video("tooEarly001", CHANNEL, RENDERED.minusDays(1), 45, marker)));
        when(youTube.videos(List.of("wrongLen001"))).thenReturn(List.of(video("wrongLen001", CHANNEL, RENDERED.plusDays(1), 60, marker)));
        when(youTube.videos(List.of("noMarker001"))).thenReturn(List.of(video("noMarker001", CHANNEL, RENDERED.plusDays(1), 45, "my film")));
        when(youTube.videos(List.of("privateVid1"))).thenReturn(List.of());

        assertThatThrownBy(() -> publishService.link(creator, project, link("otherChan01"))).hasMessageContaining("linked channel");
        assertThatThrownBy(() -> publishService.link(creator, project, link("tooEarly001"))).hasMessageContaining("before the film was made");
        assertThatThrownBy(() -> publishService.link(creator, project, link("wrongLen001"))).hasMessageContaining("length differs");
        assertThatThrownBy(() -> publishService.link(creator, project, link("noMarker001"))).hasMessageContaining("Made on Dalaillama");
        assertThatThrownBy(() -> publishService.link(creator, project, link("privateVid1"))).hasMessageContaining("make sure it is public");
        assertThatThrownBy(() -> publishService.link(creator, project, link("https://vimeo.com/1"))).hasMessageContaining("YouTube video link");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isZero();
    }

    @Test
    void anEarlierExternalPickOfTheSameVideoIsUpgraded_andDoesNotUseAnExternalSlot() {
        video(jdbc, "filmLink002", CHANNEL, 1080, 1920, NOW);
        MyShowcaseItemView picked = pickService.pick(creator, new PickVideoRequest("filmLink002", ShowcaseIndustry.BEAUTY,
                ShowcaseFormat.UGC, null, null, true));
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(youTube.videos(List.of("filmLink002"))).thenReturn(List.of(
                video("filmLink002", CHANNEL, RENDERED.plusDays(1), 45, "Made on Dalaillama")));

        MyShowcaseItemView upgraded = publishService.link(creator, project, link("filmLink002"));

        assertThat(upgraded.publicId()).isEqualTo(picked.publicId());
        assertThat(upgraded.origin()).isEqualTo(ShowcaseOrigin.PLATFORM);
    }

    @Test
    void officialUploadIsRefusedWithoutFundingOrConsent_thenQueuedOnce() {
        when(preProduction.source(creator, project)).thenReturn(source(false, true));
        assertThatThrownBy(() -> publishService.requestOfficialUpload(creator, project, official()))
                .hasMessageContaining("paid for in full");
        when(preProduction.source(creator, project)).thenReturn(source(true, false));
        assertThatThrownBy(() -> publishService.requestOfficialUpload(creator, project, official()))
                .hasMessageContaining("marketing use");

        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        assertThat(publishService.requestOfficialUpload(creator, project, official()).status()).isEqualTo(OfficialUploadStatus.QUEUED);
        assertThat(publishService.requestOfficialUpload(creator, project, official()).status()).isEqualTo(OfficialUploadStatus.QUEUED);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void theWorkerUploadsAndCreatesAnOfficialPlatformItem() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        publishService.requestOfficialUpload(creator, project, official());
        when(uploadClient.upload(any())).thenReturn(new YouTubeUploadClient.UploadResult("officialVid", "public"));
        when(youTube.videos(List.of("officialVid"))).thenReturn(List.of());

        worker.runOnce();

        assertThat(jobs.findByProjectId(project).orElseThrow().getStatus()).isEqualTo(OfficialUploadStatus.DONE);
        assertThat(jdbc.queryForObject("SELECT channel || '/' || platform_proof FROM showcase_item WHERE youtube_video_id = 'officialVid'",
                String.class)).isEqualTo("OFFICIAL/UPLOADED");
        // The film's own facts stand in until YouTube lists it: our render was vertical.
        assertThat(jdbc.queryForObject("SELECT aspect_w || 'x' || aspect_h FROM youtube_video WHERE video_id = 'officialVid'",
                String.class)).isEqualTo("1080x1920");
    }

    @Test
    void theOfficialUploadIsTheStampedCopy_andWaitsForItWithoutUsingAnAttempt() {
        ShowcaseSource ready = source(true, true);
        ShowcaseSource unstamped = new ShowcaseSource(ready.projectId(), ready.projectName(), ready.projectStatus(), true, ready.renderedAt(),
                ready.durationSeconds(), ready.width(), ready.height(), ready.clientLockedAt(), ready.marketingTermsVersion(),
                ready.marketingTermsAcceptedAt(), ready.shotImages(), ready.clientReviewSessions(), ready.clientComments(),
                ready.clientApprovals(), ready.downloadUrl(), null);
        when(preProduction.source(creator, project)).thenReturn(ready);
        publishService.requestOfficialUpload(creator, project, official());

        when(preProduction.source(creator, project)).thenReturn(unstamped);
        worker.runOnce();
        var waiting = jobs.findByProjectId(project).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(OfficialUploadStatus.QUEUED);
        assertThat(waiting.getAttempts()).isZero();
        assertThat(waiting.getError()).isEqualTo(OfficialUploadWorker.STAMP_NOTE);
        verify(uploadClient, never()).upload(any());

        when(preProduction.source(creator, project)).thenReturn(ready);
        when(uploadClient.upload(any())).thenReturn(new YouTubeUploadClient.UploadResult("stampedVid1", "public"));
        when(youTube.videos(List.of("stampedVid1"))).thenReturn(List.of());
        worker.runOnce();
        verify(uploadClient).upload(argThat(r -> r.sourceUrl().equals("https://minio/film-stamped.mp4?sig")));
        assertThat(jobs.findByProjectId(project).orElseThrow().getStatus()).isEqualTo(OfficialUploadStatus.DONE);
    }

    @Test
    void eligibleFilmsAreQueuedForOurChannelAutomatically_once() {
        jdbc.update("INSERT INTO creator_profile_industry (tenant_id, industry) VALUES (?, 'BEAUTY')", creator);
        UUID unpaid = UUID.randomUUID();
        when(preProduction.films(creator)).thenReturn(List.of(
                new PreProductionShowcaseClient.FilmSummary(project, "Serum", RENDERED, null, 1080, 1920, RENDERED, RENDERED),
                new PreProductionShowcaseClient.FilmSummary(unpaid, "Draft", RENDERED, null, 1920, 1080, null, RENDERED),
                new PreProductionShowcaseClient.FilmSummary(UUID.randomUUID(), "No consent", RENDERED, null, 1920, 1080, RENDERED, null)));
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(preProduction.source(creator, unpaid)).thenReturn(source(false, true));

        assertThat(autoQueue.run()).isEqualTo(1);
        var job = jobs.findByProjectId(project).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(OfficialUploadStatus.QUEUED);
        assertThat(job.getIndustry()).isEqualTo(ShowcaseIndustry.BEAUTY);
        assertThat(job.getFormat()).isEqualTo(ShowcaseFormat.SOCIAL_SHORT);
        assertThat(jobs.findByProjectId(unpaid)).isEmpty();

        assertThat(autoQueue.run()).isZero();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void aFilmPublishedToTheCreatorsChannelGoesOnTheProfileAsADalaillamaFilm_once() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(youTube.videos(List.of("creatorVid1"))).thenReturn(List.of());

        assertThat(publishService.recordCreatorUpload(creator, project, "creatorVid1", "public")).isPresent();
        assertThat(jdbc.queryForObject("SELECT origin || '/' || channel || '/' || platform_proof || '/' || (funded_verified_at IS NOT NULL)"
                + " FROM showcase_item WHERE youtube_video_id = 'creatorVid1'", String.class)).isEqualTo("PLATFORM/CREATOR/UPLOADED/true");
        assertThat(jdbc.queryForObject("SELECT aspect_w || 'x' || aspect_h FROM youtube_video WHERE video_id = 'creatorVid1'", String.class))
                .isEqualTo("1080x1920");

        assertThat(publishService.recordCreatorUpload(creator, project, "creatorVid1", "public")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isEqualTo(1);

        // Removed from the profile, then published again: the same item comes back live.
        jdbc.update("UPDATE showcase_item SET status = 'REMOVED' WHERE youtube_video_id = 'creatorVid1'");
        assertThat(publishService.recordCreatorUpload(creator, project, "creatorVid1", "private")).isPresent();
        assertThat(jdbc.queryForObject("SELECT status FROM showcase_item WHERE youtube_video_id = 'creatorVid1'", String.class)).isEqualTo("LIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isEqualTo(1);
    }

    @Test
    void aReRenderedFilmUploadedAgainIsTheProjectsOneItem_evenAfterItWasRemoved() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(youTube.videos(any())).thenReturn(List.of());
        publishService.recordCreatorUpload(creator, project, "creatorVid1", "public");
        jdbc.update("UPDATE showcase_item SET status = 'REMOVED'");

        // The re-rendered film is a new YouTube video. It used to be inserted as a second item for
        // the project, refused by showcase_item_project_uk on every retry, so it never showed.
        assertThat(publishService.recordCreatorUpload(creator, project, "creatorVid2", "private")).isPresent();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT youtube_video_id || '/' || status FROM showcase_item", String.class))
                .isEqualTo("creatorVid2/LIVE");
    }

    @Test
    void aReRenderedFilmOnTheProfileMovesToItsNewestUpload() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(youTube.videos(any())).thenReturn(List.of());
        publishService.recordCreatorUpload(creator, project, "creatorVid1", "public");

        assertThat(publishService.recordCreatorUpload(creator, project, "creatorVid2", "public")).isPresent();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM showcase_item", String.class)).isEqualTo("creatorVid2");
    }

    @Test
    void aFilmPublishedToTheProfileShowsWithoutYouTube_andSharingItLaterFillsInTheVideo() {
        when(preProduction.source(creator, project)).thenReturn(source(false, false));
        // No YouTube at all: a profile of platform films needs no verified channel.
        jdbc.update("DELETE FROM creator_youtube_channel");

        var published = publishService.publishToProfile(creator, project);

        assertThat(published.youtubeVideoId()).isNull();
        assertThat(published.sourceProjectId()).isEqualTo(project);
        assertThat(published.title()).isEqualTo("GlowLabs serum launch");
        assertThat(published.watchUrl()).isNull();
        assertThat(jdbc.queryForObject("SELECT platform_proof || '/' || status || '/' || film_width || 'x' || film_height"
                + " FROM showcase_item", String.class)).isEqualTo("RENDERED/LIVE/1080x1920");
        assertThat(items.findPublicForCreator(creator, NOW.atOffset(java.time.ZoneOffset.UTC).minusDays(30), 1, true)).hasSize(1);
        // Without our own copy to play there is nothing to show.
        assertThat(items.findPublicForCreator(creator, NOW.atOffset(java.time.ZoneOffset.UTC).minusDays(30), 1, false)).isEmpty();

        // Publishing again is a no-op on the same item; sharing to YouTube later fills in its video.
        publishService.publishToProfile(creator, project);
        when(youTube.videos(any())).thenReturn(List.of());
        publishService.recordCreatorUpload(creator, project, "creatorVid9", "public");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT youtube_video_id || '/' || platform_proof FROM showcase_item", String.class))
                .isEqualTo("creatorVid9/UPLOADED");
    }

    @Test
    void forcedPrivateNeedsAPerson_andRepeatedFailuresStop() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        publishService.requestOfficialUpload(creator, project, official());
        when(uploadClient.upload(any())).thenReturn(new YouTubeUploadClient.UploadResult("privVid0001", "private"));
        worker.runOnce();
        assertThat(jobs.findByProjectId(project).orElseThrow().getStatus()).isEqualTo(OfficialUploadStatus.NEEDS_MANUAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM showcase_item", Integer.class)).isZero();

        // A re-request after the manual step's failure queues it again; three failures stop it.
        publishService.requestOfficialUpload(creator, project, official());
        when(uploadClient.upload(any())).thenThrow(new RuntimeException("network"));
        worker.runOnce();
        worker.runOnce();
        assertThat(jobs.findByProjectId(project).orElseThrow().getStatus()).isEqualTo(OfficialUploadStatus.QUEUED);
        worker.runOnce();
        assertThat(jobs.findByProjectId(project).orElseThrow().getStatus()).isEqualTo(OfficialUploadStatus.FAILED);
        assertThat(jobs.findByProjectId(project).orElseThrow().getError()).isEqualTo("network");
    }

    @Test
    void aPlatformFilmPlaysInOurOwnPlayer_andAChannelPickThroughYouTube() {
        when(preProduction.source(creator, project)).thenReturn(source(true, true));
        when(youTube.videos(List.of("filmLink003"))).thenReturn(List.of(video("filmLink003", CHANNEL, RENDERED.plusDays(1), 45, "Made on Dalaillama")));
        publishService.link(creator, project, link("filmLink003"));
        video(jdbc, "extra000001", CHANNEL, 1280, 720, NOW);
        pickService.pick(creator, new PickVideoRequest("extra000001", ShowcaseIndustry.FOOD_BEVERAGE, ShowcaseFormat.UGC, null, null, true));

        var cards = publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items();
        // Made on Dalaillama: our own copy in our own player, whether or not it is also on YouTube.
        var film = cards.stream().filter(c -> c.origin() == com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin.PLATFORM).findFirst().orElseThrow();
        assertThat(film.host()).isEqualTo(VideoHostType.SELF);
        assertThat(publicService.selfSource(film.publicId())).get()
                .extracting(PublicShowcaseService.SelfSourceView::url).isEqualTo("https://minio/film-stamped.mp4?sig");
        // A pick from the creator's channel plays through YouTube.
        assertThat(cards.stream().filter(c -> c.origin() == com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin.EXTERNAL))
                .extracting(PublicShowcaseCard::host).containsOnly(VideoHostType.YOUTUBE);
    }

    private static LinkPlatformFilmRequest link(String url) {
        return new LinkPlatformFilmRequest(url, ShowcaseIndustry.FOOD_BEVERAGE, ShowcaseFormat.PRODUCT_AD, "Café chain", true);
    }

    private static OfficialUploadRequest official() {
        return new OfficialUploadRequest(ShowcaseIndustry.FOOD_BEVERAGE, ShowcaseFormat.PRODUCT_AD, "Café chain", true);
    }

    private ShowcaseSource source(boolean funded, boolean consent) {
        OffsetDateTime locked = funded ? RENDERED.plusDays(2) : null;
        return new ShowcaseSource(project, "GlowLabs serum launch", "CLIENT_LOCKED", true, RENDERED,
                new BigDecimal("45.20"), 1080, 1920, locked, consent ? "2026-10" : null, consent ? RENDERED.plusDays(2) : null,
                funded ? 12 : 0, funded ? 2 : 0, funded ? 3 : 0, 0, "https://minio/film.mp4?sig", "https://minio/film-stamped.mp4?sig");
    }

    private static VideoInfo video(String id, String channel, OffsetDateTime published, double seconds, String description) {
        return new VideoInfo(id, channel, "Film " + id, description, "https://i/" + id + ".jpg", published.toInstant(),
                Duration.ofMillis((long) (seconds * 1000)), "public", true, false, false, 1080, 1920, true);
    }
}
