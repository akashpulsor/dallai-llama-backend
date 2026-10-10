package com.dalai.llama.tenant.youtube.service;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ProfileChecklistItem;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import com.dalai.llama.tenant.youtube.client.ChannelRef;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient.ChannelInfo;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient.PlaylistPage;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient.VideoInfo;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.dto.ChannelLinkView;
import com.dalai.llama.tenant.youtube.dto.YouTubeVideoView;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Channel linking, import, listing and the 30-day refresh on a real Postgres. YouTube itself is
 * stubbed one level up from HTTP (its JSON mapping is covered by YouTubeDataClientTest). */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "youtube.api-key=test-key"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ChannelLinkService.class, ChannelImportService.class, YouTubeVideoCache.class, VideoEligibility.class,
        YouTubeRefreshJob.class, CreatorProfileService.class, HandlePolicy.class, YouTubeChannelDbTest.TestConfig.class})
class YouTubeChannelDbTest {

    private static final String CHANNEL = "UCriyaMotion0000000000ab";
    private static final String UPLOADS = "UUriyaMotion0000000000ab";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    @EnableConfigurationProperties({ShowcaseProperties.class, YouTubeProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-10-09T10:00:00Z"));
        }
    }

    @MockBean private YouTubeDataClient youTube;
    @Autowired private ChannelLinkService linkService;
    @Autowired private ChannelImportService importService;
    @Autowired private YouTubeRefreshJob refreshJob;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    @BeforeEach
    void clean() {
        clock.set(Instant.parse("2026-10-09T10:00:00Z"));
        jdbc.update("DELETE FROM youtube_video");
        jdbc.update("DELETE FROM creator_youtube_channel");
        jdbc.update("DELETE FROM creator_profile_industry");
        jdbc.update("DELETE FROM creator_public_profile");
    }

    @Test
    void linkVerifyImport_endToEnd() {
        UUID tenant = creatorWithProfile("Riya Motion");
        when(youTube.findChannel(any())).thenReturn(Optional.of(channel("Films by Riya")));

        ChannelLinkView started = linkService.start(tenant, "https://www.youtube.com/@RiyaMotion");
        assertThat(started.status()).isEqualTo(ChannelStatus.PENDING);
        assertThat(started.verificationCode()).startsWith("dalai-").hasSize(12);

        // Asking again for the same channel keeps the code the creator may already have pasted.
        assertThat(linkService.start(tenant, "@RiyaMotion").verificationCode()).isEqualTo(started.verificationCode());

        // Code not in the description yet: still pending, with an explanation.
        assertThatThrownBy(() -> linkService.verify(tenant)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(started.verificationCode());

        when(youTube.findChannel(any())).thenReturn(Optional.of(channel("Films by Riya. " + started.verificationCode().toLowerCase())));
        when(youTube.playlistItems(eq(UPLOADS), isNull())).thenReturn(new PlaylistPage(List.of("short1", "film1"), "P2"));
        when(youTube.playlistItems(UPLOADS, "P2")).thenReturn(new PlaylistPage(List.of("kids1"), null));
        when(youTube.videos(anyList())).thenReturn(List.of(
                video("short1", Duration.ofSeconds(20), 405, 720, true, false, false),
                video("film1", Duration.ofSeconds(90), 1280, 720, false, false, false),
                video("kids1", Duration.ofSeconds(60), 1280, 720, true, true, false)));

        ChannelLinkView verified = linkService.verify(tenant);

        assertThat(verified.status()).isEqualTo(ChannelStatus.VERIFIED);
        assertThat(verified.verificationCode()).isNull();
        List<YouTubeVideoView> videos = importService.listVideos(tenant);
        assertThat(videos).extracting(YouTubeVideoView::videoId).containsExactlyInAnyOrder("short1", "film1", "kids1");
        YouTubeVideoView vertical = byId(videos, "short1");
        assertThat(vertical.vertical()).isTrue();
        assertThat(vertical.eligible()).isTrue();
        assertThat(vertical.watchUrl()).isEqualTo("https://www.youtube.com/shorts/short1");
        assertThat(byId(videos, "film1").ineligibleReason()).isEqualTo("EMBEDDING_OFF");
        assertThat(byId(videos, "film1").watchUrl()).isEqualTo("https://www.youtube.com/watch?v=film1");
        assertThat(byId(videos, "kids1").ineligibleReason()).isEqualTo("MADE_FOR_KIDS");

        // The profile now uses the channel picture and no longer asks for a channel.
        assertThat(profileService.findMine(tenant).orElseThrow().avatarUrl()).isEqualTo("https://yt3/riya.jpg");
        assertThat(profileService.findMine(tenant).orElseThrow().missing())
                .doesNotContain(ProfileChecklistItem.YOUTUBE_CHANNEL, ProfileChecklistItem.AVATAR);
    }

    @Test
    void aVerifiedChannelBelongsToOneCreator_butAPendingClaimBlocksNobody() {
        UUID squatter = creatorWithProfile("Squatter");
        UUID owner = creatorWithProfile("Riya Motion");
        UUID latecomer = creatorWithProfile("Latecomer");
        when(youTube.findChannel(any())).thenReturn(Optional.of(channel("about")));

        linkService.start(squatter, "@RiyaMotion");
        String code = linkService.start(owner, "@RiyaMotion").verificationCode();
        assertThat(linkService.current(squatter)).isEmpty();

        when(youTube.findChannel(any())).thenReturn(Optional.of(channel("about " + code)));
        when(youTube.playlistItems(any(), any())).thenReturn(new PlaylistPage(List.of(), null));
        linkService.verify(owner);

        assertThatThrownBy(() -> linkService.start(latecomer, "@RiyaMotion"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("another account");
    }

    @Test
    void resyncIsRateLimited_andRemovedUploadsDisappear() {
        UUID tenant = verifiedCreator(List.of(video("a", Duration.ofSeconds(30), 1280, 720, true, false, false),
                video("b", Duration.ofSeconds(30), 1280, 720, true, false, false)));

        assertThatThrownBy(() -> importService.resync(tenant)).isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofMinutes(11));
        when(youTube.playlistItems(any(), any())).thenReturn(new PlaylistPage(List.of("a"), null));
        when(youTube.videos(anyList())).thenReturn(List.of(video("a", Duration.ofSeconds(30), 1280, 720, true, false, false)));
        importService.resync(tenant);

        assertThat(importService.listVideos(tenant)).extracting(YouTubeVideoView::videoId).containsExactly("a");
        assertThat(jdbc.queryForObject("SELECT title FROM youtube_video WHERE video_id = 'b'", String.class)).isNull();
    }

    @Test
    void refreshJob_refetchesDataOlderThanTheWindow_andHidesWhatYouTubeNoLongerServes() {
        UUID tenant = verifiedCreator(List.of(video("keep", Duration.ofSeconds(30), 1280, 720, true, false, false),
                video("gone", Duration.ofSeconds(30), 1280, 720, true, false, false)));
        clock.advance(Duration.ofDays(26));
        when(youTube.videos(anyList())).thenReturn(List.of(
                new VideoInfo("keep", CHANNEL, "Renamed film", "", "https://i/k2.jpg", Instant.parse("2026-09-01T00:00:00Z"),
                        Duration.ofSeconds(30), "public", true, false, false, 1280, 720)));
        when(youTube.findChannel(any(ChannelRef.ById.class))).thenReturn(Optional.of(channel("about")));

        refreshJob.run();

        assertThat(importService.listVideos(tenant)).extracting(YouTubeVideoView::title).containsExactly("Renamed film");
        Instant fetched = jdbc.queryForObject("SELECT fetched_at FROM youtube_video WHERE video_id = 'keep'",
                java.sql.Timestamp.class).toInstant();
        assertThat(fetched).isEqualTo(clock.instant());
        assertThat(jdbc.queryForObject("SELECT gone_at IS NOT NULL FROM youtube_video WHERE video_id = 'gone'", Boolean.class)).isTrue();
    }

    private UUID verifiedCreator(List<VideoInfo> videos) {
        UUID tenant = creatorWithProfile("Creator " + UUID.randomUUID().toString().substring(0, 4));
        when(youTube.findChannel(any())).thenReturn(Optional.of(channel("about")));
        String code = linkService.start(tenant, "@RiyaMotion").verificationCode();
        when(youTube.findChannel(any())).thenReturn(Optional.of(channel("about " + code)));
        when(youTube.playlistItems(any(), any())).thenReturn(
                new PlaylistPage(videos.stream().map(VideoInfo::videoId).toList(), null));
        when(youTube.videos(anyList())).thenReturn(videos);
        linkService.verify(tenant);
        return tenant;
    }

    private UUID creatorWithProfile(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, primary_contact_name, primary_contact_email) VALUES (?, ?, ?, ?, ?)",
                id, name, "t-" + id.toString().substring(0, 8), "Owner", "owner@example.com");
        profileService.ensureProfile(id, name);
        return id;
    }

    private static ChannelInfo channel(String description) {
        return new ChannelInfo(CHANNEL, "Riya Motion", description, "https://yt3/riya.jpg", UPLOADS);
    }

    private static VideoInfo video(String id, Duration duration, int w, int h, boolean embeddable, boolean kids, boolean age) {
        return new VideoInfo(id, CHANNEL, "Video " + id, "", "https://i/" + id + ".jpg", Instant.parse("2026-09-01T00:00:00Z"),
                duration, "public", embeddable, kids, age, w, h);
    }

    private static YouTubeVideoView byId(List<YouTubeVideoView> videos, String id) {
        return videos.stream().filter(v -> v.videoId().equals(id)).findFirst().orElseThrow();
    }
}
