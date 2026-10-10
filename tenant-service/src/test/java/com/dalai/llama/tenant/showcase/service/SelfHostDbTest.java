package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.service.VideoEligibility;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Rule 5's fallback: with {@code video-host.platform-films=SELF}, platform films play from our own
 * copy, so they stay up even when YouTube stops serving them; external videos still need YouTube. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "video-host.platform-films=SELF"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PublicShowcaseService.class, CreatorProfileService.class, HandlePolicy.class, VideoEligibility.class,
        SelfHostDbTest.TestConfig.class})
class SelfHostDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({ShowcaseProperties.class, YouTubeProperties.class, VideoHostProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private PreProductionShowcaseClient preProduction;
    @Autowired private PublicShowcaseService publicService;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void platformFilmsPlayFromOurCopyEvenWhenYouTubeDropsThem() {
        wipe(jdbc);
        UUID creator = tenant(jdbc, "Riya Motion");
        profileService.ensureProfile(creator, "Riya Motion");
        verifiedChannel(jdbc, creator, "UCriya", NOW);
        UUID project = UUID.randomUUID();
        video(jdbc, "platform001", "UCriya", 1080, 1920, NOW);
        video(jdbc, "platform002", "UCriya", 1080, 1920, NOW);
        video(jdbc, "external001", "UCriya", 1280, 720, NOW);
        item(creator, "pub0000001", "platform001", "PLATFORM", project);
        item(creator, "pub0000002", "platform002", "PLATFORM", UUID.randomUUID());
        item(creator, "pub0000003", "external001", "EXTERNAL", null);
        jdbc.update("UPDATE youtube_video SET gone_at = ?", Timestamp.from(NOW));
        when(preProduction.source(creator, project)).thenReturn(new ShowcaseSource(project, "Film", "CLIENT_LOCKED", true,
                null, new BigDecimal("30"), 1080, 1920, null, null, null, 0, 0, 0, 0, "https://minio/film.mp4?sig"));

        List<PublicShowcaseCard> cards = publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items();

        assertThat(cards).extracting(PublicShowcaseCard::publicId).containsExactlyInAnyOrder("pub0000001", "pub0000002");
        assertThat(cards).extracting(PublicShowcaseCard::host).containsOnly(VideoHostType.SELF);
        assertThat(publicService.selfSource("pub0000001")).get()
                .extracting(PublicShowcaseService.SelfSourceView::url).isEqualTo("https://minio/film.mp4?sig");
        assertThat(publicService.selfSource("pub0000003")).isEmpty();
    }

    private void item(UUID tenant, String publicId, String videoId, String origin, UUID project) {
        jdbc.update("""
                INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, source_project_id, platform_proof,
                    channel, industry, format, status, rights_confirmed_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'FOOD_BEVERAGE', 'PRODUCT_AD', 'LIVE', ?, ?)""",
                UUID.randomUUID(), publicId, tenant, videoId, origin, project,
                origin.equals("PLATFORM") ? "LINK_MATCH" : null, origin.equals("PLATFORM") ? "CREATOR" : null,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
