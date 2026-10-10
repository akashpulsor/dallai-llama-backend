package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.leadmanagement.brand.BrandDirectoryService;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.AutoPickJob;
import com.dalai.llama.tenant.leadmanagement.outreach.DailyDigestJob;
import com.dalai.llama.tenant.leadmanagement.outreach.MailAllowance;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachComposer;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachRenderer;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachService;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.FollowService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase D on a real Postgres: the ops brand import, automatic picks (spotlight first, rotation,
 * cadence, creator opt-out) and follower notices, delivered by the next digest. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "outreach.hash-pepper=pepper"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AutoPickJob.class, BrandDirectoryService.class, FollowService.class, OutreachService.class, OutreachStore.class,
        MailAllowance.class, MailableFilms.class, OutreachComposer.class, OutreachRenderer.class, RecipientHasher.class,
        DailyDigestJob.class, CreatorProfileService.class, HandlePolicy.class,
        com.dalai.llama.tenant.leadmanagement.outreach.IntentDispatcher.class,
        com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateService.class,
        com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore.class,
        com.dalai.llama.tenant.leadmanagement.audience.AudienceStore.class,
        com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator.class, AutoPickDbTest.TestConfig.class})
class AutoPickDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-07T12:30:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({OutreachProperties.class, ShowcaseProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private CreatorEmailSender creatorSender;
    @MockBean private PlatformMailer platformMailer;
    @MockBean private BillingServiceClient billing;
    @MockBean private com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver domains;
    @Autowired private AutoPickJob autoPicks;
    @Autowired private BrandDirectoryService directory;
    @Autowired private FollowService follows;
    @Autowired private DailyDigestJob digest;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID riya;
    private UUID arjun;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        riya = creator("Riya Motion", "UCriya");
        arjun = creator("Arjun Frames", "UCarjun");
        film(riya, "riya0000001", "pubriya0001", "UCriya", null);
        film(arjun, "arjun000001", "pubarjun001", "UCarjun", NOW.plus(Duration.ofHours(48))); // spotlighted
        when(platformMailer.send(any())).thenReturn(true);
    }

    @Test
    void importAddsBrandsRejectsBadLinesAndSkipsUnsubscribed() {
        jdbc.update("INSERT INTO lead_suppression (recipient_hash, reason) VALUES (?, 'UNSUBSCRIBED')",
                new RecipientHasher(props()).hash("gone@x.example"));
        BrandDirectoryService.ImportResult result = directory.importCsv("""
                email,name,company,industry,country,website
                asha@hearth.example,Asha,Hearth Foods,food_beverage,in,https://hearth.example
                gone@x.example,,,FOOD_BEVERAGE,,
                not-an-email,,,FOOD_BEVERAGE,,
                bob@b.example,,,SPACESHIPS,,
                """, true);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.skippedUnsubscribed()).isEqualTo(1);
        assertThat(result.rejectedLines()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT auto_picks_opt_in FROM lead_brand_contact WHERE email = 'asha@hearth.example'",
                Boolean.class)).isTrue();
    }

    @Test
    void picksGoSpotlightFirstThenRotateAndRespectCadence() {
        directory.importCsv("asha@hearth.example,Asha,Hearth Foods,FOOD_BEVERAGE,IN,", true);

        assertThat(autoPicks.run(NOW).picksQueued()).isEqualTo(1);
        assertThat(pendingFilm()).isEqualTo("pubarjun001");
        assertThat(autoPicks.run(NOW).picksQueued()).isZero(); // not due again for 7 days

        assertThat(digest.run().digestsSent()).isEqualTo(1);
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(platformMailer).send(mail.capture());
        assertThat(mail.getValue().text()).contains("Arjun Frames");

        clock.set(NOW.plus(Duration.ofDays(8)));
        assertThat(autoPicks.run(clock.instant()).picksQueued()).isEqualTo(1);
        assertThat(pendingFilm()).isEqualTo("pubriya0001"); // rotated to the film they haven't seen
    }

    @Test
    void aCreatorWhoTurnedPicksOffIsNeverPicked() {
        jdbc.update("UPDATE creator_public_profile SET auto_picks_enabled = FALSE WHERE tenant_id IN (?, ?)", riya, arjun);
        directory.importCsv("asha@hearth.example,,,FOOD_BEVERAGE,,", true);
        assertThat(autoPicks.run(NOW).picksQueued()).isZero();
    }

    @Test
    void followersHearAboutANewFilmInTheDigest() {
        UUID brand = UUID.randomUUID();
        jdbc.update("INSERT INTO lead_brand_contact (id, email, source) VALUES (?, 'fan@x.example', 'SIGNUP')", brand);
        follows.follow(brand, "riya-motion");
        film(riya, "riya0000002", "pubriya0002", "UCriya", null);
        jdbc.update("UPDATE showcase_item SET published_at = ? WHERE public_id = 'pubriya0002'", Timestamp.from(NOW));

        assertThat(autoPicks.run(NOW.minus(Duration.ofHours(1))).followerNoticesQueued()).isEqualTo(1);
        assertThat(digest.run().digestsSent()).isEqualTo(1);
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(platformMailer).send(mail.capture());
        assertThat(mail.getValue().text()).contains("who you follow");
    }

    private String pendingFilm() {
        return jdbc.queryForObject("""
                SELECT s.public_id FROM lead_outreach_intent i JOIN showcase_item s ON s.id = i.showcase_item_id
                WHERE i.status = 'PENDING'""", String.class);
    }

    private OutreachProperties props() {
        return new OutreachProperties("pepper", 100, 25, 14, 4, 7, 30, "https://api.example/outreach");
    }

    private UUID creator(String name, String channel) {
        UUID id = tenant(jdbc, name);
        profileService.ensureProfile(id, name);
        verifiedChannel(jdbc, id, channel, NOW);
        return id;
    }

    private void film(UUID tenant, String videoId, String publicId, String channel, Instant spotlightUntil) {
        video(jdbc, videoId, channel, 1280, 720, NOW);
        Timestamp at = Timestamp.from(NOW.minus(Duration.ofDays(5)));
        jdbc.update("""
                INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, source_project_id, platform_proof,
                    channel, client_locked_at, funded_verified_at, marketing_consent_at, industry, format, status,
                    rights_confirmed_at, published_at, spotlight_until)
                VALUES (?, ?, ?, ?, 'PLATFORM', ?, 'LINK_MATCH', 'CREATOR', ?, ?, ?, 'FOOD_BEVERAGE', 'PRODUCT_AD', 'LIVE', ?, ?, ?)""",
                UUID.randomUUID(), publicId, tenant, videoId, UUID.randomUUID(), at, at, at, at, at,
                spotlightUntil == null ? null : Timestamp.from(spotlightUntil));
    }
}
