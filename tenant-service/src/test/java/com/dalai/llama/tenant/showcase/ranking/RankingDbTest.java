package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseRankingRun;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import com.dalai.llama.tenant.showcase.service.LandingAssembler;
import com.dalai.llama.tenant.showcase.service.LandingPlacementService;
import com.dalai.llama.tenant.showcase.service.PublicShowcaseService;
import com.dalai.llama.tenant.showcase.service.ShowcaseModerationService;
import com.dalai.llama.tenant.showcase.dto.UpdatePublicProfileRequest;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The reinforcement model end to end on a real Postgres: levels, scores and floors from the
 * ranking run, the landing grid, the Top tab, the creator's ladder card, reports, the billing
 * suspension and old-handle redirects. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({com.dalai.llama.tenant.showcase.service.ShowcasePosterLinks.class, RankingRunJob.class, CreatorLadder.class, VisibilityFloor.class, ShowcaseScorer.class, CreatorFactsCollector.class,
        com.dalai.llama.tenant.leadmanagement.inquiry.InquiryConvertedRequestCounter.class, VisibilityService.class, PublicShowcaseService.class, LandingAssembler.class, LandingPlacementService.class,
        ShowcaseModerationService.class, CreatorProfileService.class, HandlePolicy.class, VideoEligibility.class,
        RankingDbTest.TestConfig.class})
class RankingDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({ShowcaseProperties.class, YouTubeProperties.class, VideoHostProperties.class,
            RankingProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    private int industryCursor;
    @MockBean private com.dalai.llama.tenant.showcase.service.ShowcaseMediaStore mediaStore;

    @MockBean private PreProductionShowcaseClient preProduction;
    @Autowired private RankingRunJob run;
    @Autowired private PublicShowcaseService publicService;
    @Autowired private LandingAssembler landing;
    @Autowired private LandingPlacementService placement;
    @Autowired private VisibilityService visibility;
    @Autowired private ShowcaseModerationService moderation;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    @BeforeEach
    void clean() {
        clock.set(NOW);
        jdbc.update("DELETE FROM showcase_ranking_run");
        jdbc.update("DELETE FROM showcase_report");
        wipe(jdbc);
    }

    @Test
    void theRunAssignsLevelsAndScores_andFundedPlatformWorkRanksFirst() {
        UUID starter = creator("Starter", 0, 0, 2);
        UUID maker = creator("Maker", 1, 0, 1);
        UUID proven = creator("Proven", 0, 1, 1);

        ShowcaseRankingRun result = run.run();

        assertThat(level(starter)).isEqualTo("L1");
        assertThat(level(maker)).isEqualTo("L2");
        assertThat(level(proven)).isEqualTo("L3");
        assertThat(result.getMaturity()).isEqualByComparingTo("0.0250");
        assertThat(result.getLandingFloor()).isEqualTo(CreatorLevel.L1);
        String top = jdbc.queryForObject("SELECT tenant_id::text FROM showcase_item ORDER BY global_score DESC LIMIT 1", String.class);
        assertThat(top).isEqualTo(proven.toString());
        assertThat(jdbc.queryForObject("SELECT score_version FROM showcase_item LIMIT 1", String.class)).isEqualTo("v2");
    }

    @Test
    void theFloorRisesOneLevelPerRunOnceFundedWorkCanFillTheLanding() {
        UUID starter = creator("Starter", 0, 0, 2);
        assertThat(run.run().getLandingFloor()).isEqualTo(CreatorLevel.L1);

        for (int n = 0; n < 9; n++) creator("Proven " + n, 0, 2, 0); // 18 funded items = 1.5 x 12
        clock.advance(Duration.ofDays(1)); // runs are nightly; each reads the one before
        assertThat(run.run().getLandingFloor()).isEqualTo(CreatorLevel.L2);
        clock.advance(Duration.ofDays(1));
        assertThat(run.run().getLandingFloor()).isEqualTo(CreatorLevel.L3);

        // The external-only starter is now below the landing floor: after every proven film on the
        // landing grid (only room left), still on the All tab and their own page. The Top tab is
        // sized for 24 (36 items needed), so 18 funded films don't lift its floor yet.
        List<String> grid = landing.assemble().stream().map(card -> card.creator().displayName()).toList();
        assertThat(grid).isNotEmpty();
        assertThat(grid.get(0)).startsWith("Proven");
        int firstStarter = grid.indexOf("Starter");
        if (firstStarter >= 0) {
            assertThat(grid.subList(firstStarter, grid.size())).allMatch("Starter"::equals);
        }
        assertThat(jdbc.queryForObject("SELECT top_floor FROM showcase_ranking_run ORDER BY run_at DESC LIMIT 1", String.class))
                .isEqualTo("L1");
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.ALL, null, null, 0).items())
                .anyMatch(card -> card.creator().displayName().equals("Starter"));
        assertThat(visibility.mine(starter).orElseThrow().eligibleForLanding()).isFalse();
    }

    @Test
    void landingPutsSpotlightsFirst_capsEachCreator_andHidesWhenThin() {
        UUID a = creator("Alpha", 0, 0, 3);
        assertThat(landing.assemble()).as("one creator is enough to show (min 1); with room, all their work shows").hasSize(3);

        creator("Beta", 0, 0, 3);
        UUID gamma = creator("Gamma", 0, 1, 2);
        jdbc.update("UPDATE showcase_item SET spotlight_until = ? WHERE tenant_id = ? AND origin = 'PLATFORM'",
                Timestamp.from(NOW.plus(Duration.ofHours(48))), gamma);
        run.run();

        List<PublicShowcaseCard> grid = landing.assemble();
        assertThat(grid.get(0).creator().displayName()).isEqualTo("Gamma");
        assertThat(grid.get(0).origin().name()).isEqualTo("PLATFORM");
        // The caps keep variety first: each creator's third film comes only after everyone's first two.
        List<String> names = grid.stream().map(c -> c.creator().displayName()).toList();
        assertThat(names.subList(0, 6)).filteredOn("Alpha"::equals).hasSizeLessThanOrEqualTo(2);
        assertThat(grid).as("with room in the grid, no film is left out").hasSize(9);
    }

    @Test
    void theCapsStillHoldWhenTheGridIsFull() {
        for (int n = 0; n < 8; n++) creator("Maker " + n, 0, 0, 3); // 24 films, grid of 12
        run.run();

        List<String> names = landing.assemble().stream().map(c -> c.creator().displayName()).toList();

        assertThat(names).hasSize(12);
        assertThat(names.stream().distinct().count()).as("12 slots, at most 2 per creator").isGreaterThanOrEqualTo(6);
    }

    @Test
    void opsPlaceFilmsOnTheLandingOnly_heroesFirstNewestFirst_removedOnesStayOnTheProfile() {
        creator("Alpha", 0, 0, 2);
        creator("Beta", 0, 0, 2);
        run.run();
        List<String> ids = jdbc.queryForList("SELECT public_id FROM showcase_item ORDER BY public_id", String.class);
        String older = ids.get(0), newer = ids.get(1), removed = ids.get(2);

        assertThat(landing.hero()).isEmpty();
        placement.place(older, false, true);
        clock.advance(Duration.ofMinutes(1));
        placement.place(newer, false, true);
        placement.place(removed, true, false);

        assertThat(landing.hero()).extracting(PublicShowcaseCard::publicId).containsExactly(newer, older);
        List<PublicShowcaseCard> grid = landing.assemble();
        assertThat(grid).extracting(PublicShowcaseCard::publicId).startsWith(newer, older).doesNotContain(removed);
        assertThat(publicService.feed(PublicShowcaseService.FeedTab.ALL, null, null, 0).items())
                .as("taking a film off the landing leaves it on the profile and in the feeds")
                .extracting(PublicShowcaseCard::publicId).contains(removed);

        // Saving a hero again keeps its place in the hero order; a removed film can come back.
        placement.place(older, false, true);
        assertThat(landing.hero()).extracting(PublicShowcaseCard::publicId).containsExactly(newer, older);
        placement.place(removed, false, false);
        assertThat(landing.assemble()).extracting(PublicShowcaseCard::publicId).contains(removed);
        assertThat(placement.list()).filteredOn(v -> v.publicId().equals(newer)).singleElement()
                .satisfies(v -> assertThat(v.landingHeroAt()).isNotNull());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> placement.place(older, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creatorsBelowTheFloorFillTheLandingWhenThereIsRoom() {
        UUID proven = creator("Proven", 0, 2, 0);
        UUID newcomer = creator("Newcomer", 0, 0, 2);
        jdbc.update("UPDATE creator_public_profile SET level = 'L3' WHERE tenant_id = ?", proven);
        jdbc.update("UPDATE creator_public_profile SET level = 'L0' WHERE tenant_id = ?", newcomer);
        jdbc.update("INSERT INTO showcase_ranking_run (id, run_at, maturity, landing_floor, top_floor, auto_floor, config_version, creators_ranked, items_scored) "
                + "VALUES (?, ?, 0, 'L3', 'L1', 'L1', 'v2', 2, 4)", UUID.randomUUID(), Timestamp.from(NOW));

        List<String> grid = landing.assemble().stream().map(card -> card.creator().displayName()).toList();

        assertThat(grid).as("the floor orders; it no longer leaves the landing without creators' films")
                .containsExactly("Proven", "Proven", "Newcomer", "Newcomer");
    }

    @Test
    void theLadderCardNamesTheNextStep() {
        UUID starter = creator("Starter", 0, 0, 2);
        run.run();

        VisibilityService.VisibilityView view = visibility.mine(starter).orElseThrow();

        assertThat(view.level()).isEqualTo(CreatorLevel.L1);
        assertThat(view.nextLevel()).isEqualTo(CreatorLevel.L2);
        assertThat(view.nextStep()).contains("made on Dalaillama");
        assertThat(view.eligibleForLanding()).isTrue();
    }

    @Test
    void threeDistinctReportsHideAnItem_andRepeatsDoNotCount() {
        creator("Riya", 0, 0, 2);
        String publicId = jdbc.queryForObject("SELECT public_id FROM showcase_item LIMIT 1", String.class);
        UUID visitor = UUID.randomUUID();

        moderation.report(publicId, visitor, ShowcaseModerationService.ReportReason.RIGHTS, "my ad");
        moderation.report(publicId, visitor, ShowcaseModerationService.ReportReason.RIGHTS, "again");
        moderation.report(publicId, UUID.randomUUID(), ShowcaseModerationService.ReportReason.OFFENSIVE, null);
        assertThat(hidden(publicId)).isFalse();
        moderation.report(publicId, UUID.randomUUID(), ShowcaseModerationService.ReportReason.OTHER, null);

        assertThat(hidden(publicId)).isTrue();
        assertThat(moderation.reported()).hasSize(1).first().satisfies(i -> assertThat(i.getReportCount()).isEqualTo(3));
    }

    @Test
    void aLapsedSubscriptionTakesTheProfileDown_andOpsHidingWins() {
        UUID riya = creator("Riya Motion", 0, 0, 2);
        moderation.onBillingState(riya, "BLOCKED");
        assertThat(publicService.profile("riya-motion")).isEmpty();
        moderation.onBillingState(riya, "GRACE");
        assertThat(publicService.profile("riya-motion")).isPresent();

        moderation.setProfileStatus("riya-motion", ProfileStatus.HIDDEN);
        moderation.onBillingState(riya, "ACTIVE");
        assertThat(publicService.profile("riya-motion")).isEmpty();
    }

    @Test
    void anOldHandleRedirectsWithinTheWindow() {
        UUID riya = creator("Riya Motion", 0, 0, 2);
        profileService.update(riya, new UpdatePublicProfileRequest("riya-films", "Riya Motion", "Films", null, null, null,
                Set.of(ShowcaseIndustry.FOOD_BEVERAGE), true));

        assertThat(publicService.movedHandle("riya-motion")).contains("riya-films");
        clock.advance(Duration.ofDays(91));
        assertThat(publicService.movedHandle("riya-motion")).isEmpty();
    }

    /** A ready creator (verified channel, headline, industry) with the given items. */
    private UUID creator(String name, int unfundedPlatform, int fundedPlatform, int external) {
        UUID id = tenant(jdbc, name);
        profileService.ensureProfile(id, name);
        jdbc.update("UPDATE creator_public_profile SET headline = 'Films' WHERE tenant_id = ?", id);
        jdbc.update("INSERT INTO creator_profile_industry (tenant_id, industry) VALUES (?, 'FOOD_BEVERAGE')", id);
        String channel = "UC" + id.toString().substring(0, 8);
        verifiedChannel(jdbc, id, channel, NOW);
        for (int n = 0; n < unfundedPlatform + fundedPlatform + external; n++) {
            String videoId = id.toString().substring(0, 6) + "v" + n;
            video(jdbc, videoId, channel, 1280, 720, NOW);
            boolean platform = n < unfundedPlatform + fundedPlatform;
            boolean funded = platform && n >= unfundedPlatform;
            jdbc.update("""
                    INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, source_project_id,
                        platform_proof, channel, client_locked_at, funded_verified_at, industry, format, status,
                        rights_confirmed_at, published_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PRODUCT_AD', 'LIVE', ?, ?)""",
                    UUID.randomUUID(), videoId, id, videoId, platform ? "PLATFORM" : "EXTERNAL",
                    platform ? UUID.randomUUID() : null, platform ? "LINK_MATCH" : null, platform ? "CREATOR" : null,
                    funded ? Timestamp.from(NOW.minus(Duration.ofDays(3))) : null,
                    funded ? Timestamp.from(NOW.minus(Duration.ofDays(1))) : null,
                    // Round-robin industries so the per-industry cap doesn't hide what a test is looking at.
                    ShowcaseIndustry.values()[industryCursor++ % ShowcaseIndustry.values().length].name(),
                    Timestamp.from(NOW), Timestamp.from(NOW.minus(Duration.ofDays(n))));
        }
        return id;
    }

    private String level(UUID tenant) {
        return jdbc.queryForObject("SELECT level FROM creator_public_profile WHERE tenant_id = ?", String.class, tenant);
    }

    private boolean hidden(String publicId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT hidden_by_ops FROM showcase_item WHERE public_id = ?",
                Boolean.class, publicId));
    }
}
