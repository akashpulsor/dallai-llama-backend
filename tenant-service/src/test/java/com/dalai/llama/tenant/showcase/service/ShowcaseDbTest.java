package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ProfileChecklistItem;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PickVideoRequest;
import com.dalai.llama.tenant.showcase.dto.PublicCreatorProfileView;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.showcase.dto.UpdateShowcaseItemRequest;
import com.dalai.llama.tenant.showcase.repository.ShowcasePlayStore;
import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.service.VideoEligibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Picks, the public profile and feed, and play counting on a real Postgres, including the one
 * shared "may be shown publicly" query. Videos and channels are seeded straight into the cache
 * tables, as the import would leave them. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ShowcasePickService.class, PublicShowcaseService.class, ShowcaseEngagementService.class, ShowcasePlayStore.class,
        PublicIdGenerator.class, CreatorProfileService.class, HandlePolicy.class, VideoEligibility.class,
        ShowcaseDbTest.TestConfig.class})
class ShowcaseDbTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    @EnableConfigurationProperties({ShowcaseProperties.class, YouTubeProperties.class, com.dalai.llama.tenant.showcase.config.VideoHostProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-10-09T10:00:00Z"));
        }
    }

    @org.springframework.boot.test.mock.mockito.MockBean private com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient preProduction;
    @Autowired private ShowcasePickService pickService;
    @Autowired private PublicShowcaseService publicService;
    @Autowired private ShowcaseEngagementService engagementService;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    @BeforeEach
    void clean() {
        clock.set(Instant.parse("2026-10-09T10:00:00Z"));
        jdbc.update("DELETE FROM showcase_play");
        jdbc.update("DELETE FROM showcase_item");
        jdbc.update("DELETE FROM youtube_video");
        jdbc.update("DELETE FROM creator_youtube_channel");
        jdbc.update("DELETE FROM creator_profile_industry");
        jdbc.update("DELETE FROM creator_public_profile");
    }

    @Test
    void aProfileGoesPublicOnlyOnceItHasEnoughPicks() {
        UUID tenant = creatorWithChannel("Riya Motion", "UCriya");
        video("short1", "UCriya", 405, 720, true);
        video("film1", "UCriya", 1280, 720, true);

        pickService.pick(tenant, pick("short1"));
        PublicCreatorProfileView notYet = publicService.profile("riya-motion").orElseThrow();
        assertThat(notYet.ready()).isFalse();
        assertThat(notYet.items()).isEmpty();
        assertThat(profileService.findMine(tenant).orElseThrow().missing()).contains(ProfileChecklistItem.SHOWCASE_PICKS);

        pickService.pick(tenant, pick("film1"));
        PublicCreatorProfileView ready = publicService.profile("riya-motion").orElseThrow();
        assertThat(ready.ready()).isTrue();
        assertThat(ready.items()).extracting(PublicShowcaseCard::youtubeVideoId).containsExactly("short1", "film1");
        PublicShowcaseCard vertical = ready.items().get(0);
        assertThat(vertical.vertical()).isTrue();
        assertThat(vertical.watchUrl()).isEqualTo("https://www.youtube.com/shorts/short1");
        assertThat(vertical.creator().profileUrl()).isEqualTo("https://dalaillama.in/c/riya-motion");
        assertThat(profileService.findMine(tenant).orElseThrow().missing()).doesNotContain(ProfileChecklistItem.SHOWCASE_PICKS);
    }

    @Test
    void picksMustBeTheCreatorsOwnShowableVideos_andAreCapped() {
        UUID tenant = creatorWithChannel("Riya Motion", "UCriya");
        creatorWithChannel("Other", "UCother");
        video("theirs", "UCother", 1280, 720, true);
        video("noembed", "UCriya", 1280, 720, false);
        for (int i = 1; i <= 7; i++) video("v" + i, "UCriya", 1280, 720, true);

        assertThatThrownBy(() -> pickService.pick(tenant, pick("theirs"))).hasMessageContaining("isn't on your linked channel");
        assertThatThrownBy(() -> pickService.pick(tenant, pick("noembed"))).hasMessageContaining("EMBEDDING_OFF");
        for (int i = 1; i <= 6; i++) pickService.pick(tenant, pick("v" + i));
        assertThatThrownBy(() -> pickService.pick(tenant, pick("v7"))).hasMessageContaining("up to 6");
        assertThatThrownBy(() -> pickService.pick(tenant, pick("v1"))).hasMessageContaining("already on your profile");

        // Hiding one makes room; removing and re-picking revives the same item and public id.
        MyShowcaseItemView first = pickService.listMine(tenant).get(0);
        pickService.update(tenant, first.id(), new UpdateShowcaseItemRequest(ShowcaseIndustry.BEAUTY, ShowcaseFormat.UGC, null, null, false));
        pickService.pick(tenant, pick("v7"));
        pickService.remove(tenant, first.id());
        assertThatThrownBy(() -> pickService.pick(tenant, pick(first.youtubeVideoId()))).hasMessageContaining("up to 6");
    }

    @Test
    void feedFiltersByIndustry_andHidesWhatMustNotBeShown() {
        UUID riya = creatorWithChannel("Riya Motion", "UCriya");
        UUID kabir = creatorWithChannel("Kabir Frames", "UCkabir");
        video("r1", "UCriya", 1280, 720, true);
        video("r2", "UCriya", 1280, 720, true);
        video("k1", "UCkabir", 1280, 720, true);
        video("k2", "UCkabir", 1280, 720, true);
        pickService.pick(riya, pick("r1", ShowcaseIndustry.FOOD_BEVERAGE));
        pickService.pick(riya, pick("r2", ShowcaseIndustry.FASHION));
        pickService.pick(kabir, pick("k1", ShowcaseIndustry.FOOD_BEVERAGE));
        pickService.pick(kabir, pick("k2", ShowcaseIndustry.FOOD_BEVERAGE));

        assertThat(publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items()).hasSize(4);
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.NEW, ShowcaseIndustry.FOOD_BEVERAGE, null, 0).items())
                .extracting(PublicShowcaseCard::youtubeVideoId).containsExactlyInAnyOrder("r1", "k1", "k2");

        // Ops kill switch on one item drops Kabir below the minimum, so all his work leaves.
        jdbc.update("UPDATE showcase_item SET hidden_by_ops = TRUE WHERE youtube_video_id = 'k2'");
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items()).extracting(PublicShowcaseCard::youtubeVideoId)
                .containsExactlyInAnyOrder("r1", "r2");

        // A video YouTube stopped serving disappears; so does all cached data older than 30 days.
        jdbc.update("UPDATE youtube_video SET gone_at = NOW() WHERE video_id = 'r2'");
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items()).isEmpty();
        jdbc.update("UPDATE youtube_video SET gone_at = NULL");
        jdbc.update("UPDATE showcase_item SET hidden_by_ops = FALSE");
        clock.advance(Duration.ofDays(31));
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0).items()).isEmpty();

        // A suspended profile is not found at all.
        jdbc.update("UPDATE creator_public_profile SET status = 'SUSPENDED' WHERE tenant_id = ?", kabir);
        assertThat(publicService.profile("kabir-frames")).isEmpty();
    }

    @Test
    void publicPayloadsCarryNoInternalIds() throws Exception {
        UUID tenant = creatorWithChannel("Riya Motion", "UCriya");
        video("a", "UCriya", 1280, 720, true);
        video("b", "UCriya", 1280, 720, true);
        UUID itemId = pickService.pick(tenant, pick("a")).id();
        pickService.pick(tenant, pick("b"));

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        String profile = json.writeValueAsString(publicService.profile("riya-motion").orElseThrow());
        String feed = json.writeValueAsString(publicService.feed(PublicShowcaseService.FeedTab.NEW, null, null, 0));

        for (String body : List.of(profile, feed)) {
            assertThat(body).doesNotContain(tenant.toString()).doesNotContain(itemId.toString()).doesNotContain("@example.com");
        }
    }

    @Test
    void playsCountOncePerVisitorPerDay() {
        UUID tenant = creatorWithChannel("Riya Motion", "UCriya");
        video("a", "UCriya", 1280, 720, true);
        String publicId = pickService.pick(tenant, pick("a")).publicId();
        UUID visitor = UUID.randomUUID();

        engagementService.recordPlay(publicId, visitor, false);
        engagementService.recordPlay(publicId, visitor, false);
        engagementService.recordPlay(publicId, visitor, true);
        engagementService.recordPlay(publicId, visitor, true);
        engagementService.recordPlay(publicId, UUID.randomUUID(), false);
        clock.advance(Duration.ofDays(1));
        engagementService.recordPlay(publicId, visitor, false);

        MyShowcaseItemView item = pickService.listMine(tenant).get(0);
        assertThat(item.playCount()).isEqualTo(3);
        assertThat(item.fullPlayCount()).isEqualTo(1);
        assertThatThrownBy(() -> engagementService.recordPlay("nope", visitor, false)).isInstanceOf(IllegalArgumentException.class);
    }

    private PickVideoRequest pick(String videoId) {
        return pick(videoId, ShowcaseIndustry.FOOD_BEVERAGE);
    }

    private PickVideoRequest pick(String videoId, ShowcaseIndustry industry) {
        return new PickVideoRequest(videoId, industry, ShowcaseFormat.PRODUCT_AD, "D2C snack brand", null, true);
    }

    private UUID creatorWithChannel(String name, String channelId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, primary_contact_name, primary_contact_email) VALUES (?, ?, ?, ?, ?)",
                id, name, "t-" + id.toString().substring(0, 8), "Owner", "owner@example.com");
        profileService.ensureProfile(id, name);
        jdbc.update("""
                INSERT INTO creator_youtube_channel (tenant_id, channel_id, channel_title, channel_thumbnail_url,
                    uploads_playlist_id, verification_code, status, verified_at, fetched_at)
                VALUES (?, ?, ?, ?, ?, 'dalai-XXXXXX', 'VERIFIED', ?, ?)""",
                id, channelId, name, "https://yt3/" + channelId + ".jpg", "UU" + channelId, now(), now());
        return id;
    }

    private void video(String id, String channelId, int w, int h, boolean embeddable) {
        jdbc.update("""
                INSERT INTO youtube_video (video_id, channel_id, title, thumbnail_url, published_at, duration_seconds,
                    aspect_w, aspect_h, privacy_status, embeddable, age_restricted, made_for_kids, fetched_at)
                VALUES (?, ?, ?, ?, ?, 30, ?, ?, 'public', ?, FALSE, FALSE, ?)""",
                id, channelId, "Video " + id, "https://i/" + id + ".jpg", now(), w, h, embeddable, now());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
