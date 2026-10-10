package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailMessage;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.email.EmailSendResult;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.DailyDigestJob;
import com.dalai.llama.tenant.leadmanagement.outreach.MailAllowance;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachComposer;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Outcome;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Recipient;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachRenderer;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachService;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachTemplate;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase C on a real Postgres: one mail per recipient per day (now, or in the next digest), the
 * creator cooldown, the weekly allowance and paid packs, unsubscribe, tracked links and the purge.
 * SMTP and billing are the only fakes. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "outreach.free-per-week=3", "outreach.hash-pepper=pepper"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OutreachService.class, OutreachStore.class, MailAllowance.class, MailableFilms.class, OutreachComposer.class,
        OutreachRenderer.class, RecipientHasher.class, DailyDigestJob.class, CreatorProfileService.class, HandlePolicy.class,
        OutreachDbTest.TestConfig.class})
class OutreachDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z"); // a Wednesday

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
    @Autowired private OutreachService outreach;
    @Autowired private DailyDigestJob digest;
    @Autowired private OutreachStore store;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID riya;
    private UUID arjun;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        riya = creator("Riya Motion", "UCriya", "riya0000001", "pubriya0001", true);
        arjun = creator("Arjun Frames", "UCarjun", "arjun000001", "pubarjun001", true);
        film(riya, "riyaext0001", "pubriyaext1", "UCriya", false);
        when(creatorSender.send(any())).thenReturn(EmailSendResult.accepted(null));
        when(platformMailer.send(any())).thenReturn(true);
    }

    @Test
    void oneMailADay_theSecondCreatorWaitsForTomorrowsDigest() {
        assertThat(send(riya, "pubriya0001", "buyer@hearth.example").results().get(0).outcome()).isEqualTo(Outcome.SENT);
        ArgumentCaptor<CreatorEmailMessage> mail = ArgumentCaptor.forClass(CreatorEmailMessage.class);
        verify(creatorSender).send(mail.capture());
        assertThat(mail.getValue().fromCreatorId()).isEqualTo(riya);
        assertThat(mail.getValue().headers()).containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        assertThat(mail.getValue().bodyText()).contains("https://api.dalaillama.in/api/v1/public/outreach/r/");

        assertThat(send(arjun, "pubarjun001", "Buyer@Hearth.example").results().get(0).outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(digest.run().digestsSent()).isZero(); // they already had today's mail

        clock.set(NOW.plus(Duration.ofDays(1)));
        assertThat(digest.run().digestsSent()).isEqualTo(1);
        ArgumentCaptor<PlatformMailer.Mail> digestMail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(platformMailer).send(digestMail.capture());
        assertThat(digestMail.getValue().to()).isEqualTo("buyer@hearth.example");
        assertThat(digestMail.getValue().text()).contains("Arjun Frames");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_intent WHERE status = 'DIGESTED'", Integer.class)).isEqualTo(1);
        assertThat(digest.run().digestsSent()).isZero();
    }

    @Test
    void theSameCreatorWaitsTwoWeeksBeforeMailingTheSamePersonAgain() {
        SendResult first = outreach.send(riya, new SendRequest(OutreachTemplate.SHOWCASE_WORK, "pubriya0001", null,
                List.of(new Recipient("a@x.example", null, null), new Recipient("A@x.example ", null, null))));
        assertThat(first.results()).extracting(r -> r.outcome()).containsExactly(Outcome.SENT, Outcome.DUPLICATE);

        clock.set(NOW.plus(Duration.ofDays(13)));
        assertThat(send(riya, "pubriya0001", "a@x.example").results().get(0).outcome()).isEqualTo(Outcome.COOLDOWN);
        clock.set(NOW.plus(Duration.ofDays(15)));
        assertThat(send(riya, "pubriya0001", "a@x.example").results().get(0).outcome()).isEqualTo(Outcome.SENT);
    }

    @Test
    void freeMailsRunOutThenAPaidPackIsUsedAndARetriedPurchaseIsNotDoubled() {
        SendResult sent = outreach.send(riya, new SendRequest(OutreachTemplate.CREATOR_PORTFOLIO, null, null, List.of(
                new Recipient("1@x.example", null, null), new Recipient("2@x.example", null, null),
                new Recipient("3@x.example", null, null), new Recipient("4@x.example", null, null))));
        assertThat(sent.results()).extracting(r -> r.outcome())
                .containsExactly(Outcome.SENT, Outcome.SENT, Outcome.SENT, Outcome.OVER_ALLOWANCE);
        assertThat(sent.allowance().left()).isZero();

        when(billing.purchaseAddon(eq(riya), eq("OUTREACH_MAIL_PACK"), anyString()))
                .thenReturn(new BillingServiceClient.AddonPurchase("OUTREACH_MAIL_PACK", 500, new BigDecimal("499"), "INR", BigDecimal.TEN));
        outreach.buyPack(riya, "k1");
        assertThat(outreach.buyPack(riya, "k1").allowance().packMailsLeft()).isEqualTo(500);

        assertThat(send(riya, "pubriya0001", "4@x.example").results().get(0).outcome()).isEqualTo(Outcome.SENT);
        assertThat(jdbc.queryForObject("SELECT remaining FROM lead_mail_pack", Integer.class)).isEqualTo(499);

        // A new ISO week brings the free mails back.
        clock.set(Instant.parse("2026-10-12T00:00:01Z"));
        assertThat(outreach.overview(riya).allowance().freeUsedThisWeek()).isZero();
    }

    @Test
    void unsubscribingStopsEverythingIncludingWhatIsQueued() {
        send(riya, "pubriya0001", "gone@x.example");
        send(arjun, "pubarjun001", "gone@x.example"); // queued
        String token = jdbc.queryForObject("SELECT unsubscribe_token FROM lead_outreach_delivery", String.class);

        outreach.unsubscribe(token);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_intent WHERE status = 'PENDING'", Integer.class)).isZero();
        clock.set(NOW.plus(Duration.ofDays(20)));
        assertThat(send(arjun, "pubarjun001", "gone@x.example").results().get(0).outcome()).isEqualTo(Outcome.SUPPRESSED);
        assertThat(digest.run().digestsSent()).isZero();
    }

    @Test
    void onlyPaidForConsentedPlatformFilmsCanBeMailed() {
        assertThatThrownBy(() -> send(riya, "pubriyaext1", "x@x.example")).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE showcase_item SET marketing_consent_at = NULL WHERE public_id = 'pubriya0001'");
        assertThatThrownBy(() -> send(riya, "pubriya0001", "x@x.example")).isInstanceOf(IllegalStateException.class);
        verify(creatorSender, times(0)).send(any());
    }

    @Test
    void trackedLinksCountClicksCarryTheRefAndRawAddressesArePurgedAfter30Days() {
        send(riya, "pubriya0001", "click@x.example");
        String token = jdbc.queryForObject("SELECT token FROM lead_outreach_link", String.class);

        assertThat(store.click(token)).get().asString()
                .isEqualTo("https://dalaillama.in/c/riya-motion?industry=FOOD_BEVERAGE&ref=" + token);
        assertThat(jdbc.queryForObject("SELECT click_count FROM lead_outreach_link", Integer.class)).isEqualTo(1);

        store.purgeBefore(NOW.plus(Duration.ofDays(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_intent WHERE recipient_email IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_delivery WHERE recipient_email IS NOT NULL", Integer.class)).isZero();
        // The cooldown still holds: it keys on the hash.
        assertThat(send(riya, "pubriya0001", "click@x.example").results().get(0).outcome()).isEqualTo(Outcome.COOLDOWN);
    }

    private SendResult send(UUID tenant, String publicId, String email) {
        return outreach.send(tenant, new SendRequest(OutreachTemplate.SHOWCASE_WORK, publicId, "Hello",
                List.of(new Recipient(email, "Asha", "Hearth"))));
    }

    private UUID creator(String name, String channel, String videoId, String publicId, boolean mailable) {
        UUID id = tenant(jdbc, name);
        profileService.ensureProfile(id, name);
        verifiedChannel(jdbc, id, channel, NOW);
        film(id, videoId, publicId, channel, mailable);
        return id;
    }

    private void film(UUID tenant, String videoId, String publicId, String channel, boolean platform) {
        video(jdbc, videoId, channel, 1280, 720, NOW);
        Timestamp at = Timestamp.from(NOW.minus(Duration.ofDays(2)));
        jdbc.update("""
                INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, source_project_id, platform_proof,
                    channel, client_locked_at, funded_verified_at, marketing_consent_at, industry, format, status,
                    rights_confirmed_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FOOD_BEVERAGE', 'PRODUCT_AD', 'LIVE', ?, ?)""",
                UUID.randomUUID(), publicId, tenant, videoId, platform ? "PLATFORM" : "EXTERNAL",
                platform ? UUID.randomUUID() : null, platform ? "LINK_MATCH" : null, platform ? "CREATOR" : null,
                platform ? at : null, platform ? at : null, platform ? at : null, at, at);
    }
}
