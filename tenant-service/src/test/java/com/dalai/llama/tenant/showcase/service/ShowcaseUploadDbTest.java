package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.showcase.dto.UploadShowcaseFilmRequest;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.service.VideoEligibility;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A video the creator uploads to their profile -- typically private on YouTube -- shows as
 * External, plays our copy in our own player and uses the thumbnail they picked. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "showcase.picks.min-initial-picks=1"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ShowcaseUploadService.class, ShowcasePosterLinks.class, PublicShowcaseService.class, PublicIdGenerator.class,
        CreatorProfileService.class, HandlePolicy.class, VideoEligibility.class, ShowcaseUploadDbTest.TestConfig.class})
class ShowcaseUploadDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({ShowcaseProperties.class, YouTubeProperties.class, VideoHostProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private ShowcaseMediaStore mediaStore;
    @MockBean private PreProductionShowcaseClient preProduction;
    @Autowired private ShowcaseUploadService uploadService;
    @Autowired private PublicShowcaseService publicService;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;

    private UUID creator;

    @BeforeEach
    void setUp() {
        wipe(jdbc);
        // No verified channel: an uploaded video needs none, it is not played from YouTube.
        creator = tenant(jdbc, "Akash");
        profileService.ensureProfile(creator, "Akash");
    }

    @Test
    void anUploadIsAnExternalFilmThatPlaysOurCopyWithTheChosenThumbnail() {
        when(mediaStore.link(any())).thenAnswer(a -> "https://media/" + a.getArgument(0) + "?sig");

        MyShowcaseItemView mine = uploadService.upload(creator, video("video/mp4"), poster(), request("Diwali ad", true));

        assertThat(mine.origin()).isEqualTo(ShowcaseOrigin.EXTERNAL);
        assertThat(mine.title()).isEqualTo("Diwali ad");
        assertThat(mine.thumbnailUrl()).endsWith("/showcase/" + mine.publicId() + "/poster");
        verify(mediaStore).put(startsWith("showcase-uploads/" + creator + "/"), any(), eq("video/mp4"));
        verify(mediaStore).put(org.mockito.ArgumentMatchers.endsWith("-poster.jpg"), any(), eq("image/jpeg"));

        List<PublicShowcaseCard> cards = publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items();
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.publicId()).isEqualTo(mine.publicId());
            assertThat(card.host()).isEqualTo(VideoHostType.SELF);
            assertThat(card.origin()).isEqualTo(ShowcaseOrigin.EXTERNAL);
            assertThat(card.thumbnailUrl()).endsWith("/poster");
        });
        assertThat(publicService.selfSource(mine.publicId())).get()
                .extracting(PublicShowcaseService.SelfSourceView::url).asString().endsWith(".mp4?sig");
        assertThat(publicService.poster(mine.publicId())).get().asString().endsWith("-poster.jpg?sig");

        // Taken off the profile: no longer public, and no link to it is handed out.
        jdbc.update("UPDATE showcase_item SET status = 'REMOVED' WHERE public_id = ?", mine.publicId());
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items()).isEmpty();
        assertThat(publicService.selfSource(mine.publicId())).isEmpty();
    }

    @Test
    void anUploadNeedsAVideoAThumbnailATitleAndTheCreatorsRights() {
        assertThatThrownBy(() -> uploadService.upload(creator, video("image/gif"), poster(), request("Ad", true)))
                .hasMessageContaining("MP4, MOV or WebM");
        assertThatThrownBy(() -> uploadService.upload(creator, video("video/mp4"), null, request("Ad", true)))
                .hasMessageContaining("Pick a thumbnail");
        assertThatThrownBy(() -> uploadService.upload(creator, video("video/mp4"), poster(), request(" ", true)))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> uploadService.upload(creator, video("video/mp4"), poster(),
                new UploadShowcaseFilmRequest("Ad", BigDecimal.TEN, 1080, 1920, null, null, null, true)))
                .hasMessageContaining("industry and format");
        assertThatThrownBy(() -> uploadService.upload(creator, video("video/mp4"), poster(), request("Ad", false)))
                .hasMessageContaining("yours to show");
        verify(mediaStore, never()).put(any(), any(), any());
    }

    private static MockMultipartFile video(String type) {
        return new MockMultipartFile("video", "ad.mp4", type, new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'});
    }

    private static MockMultipartFile poster() {
        return new MockMultipartFile("poster", "frame.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private static UploadShowcaseFilmRequest request(String title, boolean rights) {
        return new UploadShowcaseFilmRequest(title, new BigDecimal("31.2"), 1080, 1920,
                com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.FOOD_BEVERAGE, com.dalai.llama.tenant.showcase.domain.ShowcaseFormat.values()[0], "D2C snacks brand", rights);
    }
}
