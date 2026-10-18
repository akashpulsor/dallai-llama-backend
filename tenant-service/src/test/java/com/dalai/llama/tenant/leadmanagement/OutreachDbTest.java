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
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
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
 * daily send cap, the creator cooldown, the weekly allowance and paid packs, unsubscribe, tracked
 * links and the purge.
 * SMTP and billing are the only fakes. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "outreach.free-per-week=3", "outreach.hash-pepper=pepper", "outreach.daily-send-cap=5"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({OutreachService.class, OutreachStore.class, MailAllowance.class, MailableFilms.class, OutreachComposer.class,
        com.dalai.llama.tenant.leadmanagement.outreach.MailIdeas.class, com.dalai.llama.tenant.showcase.inspiration.InspirationStore.class,
        MailRenderer.class, RecipientHasher.class, DailyDigestJob.class,
        com.dalai.llama.tenant.leadmanagement.outreach.OutreachDispatchJob.class, CreatorProfileService.class, HandlePolicy.class,
        com.dalai.llama.tenant.leadmanagement.outreach.IntentDispatcher.class,
        com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateService.class,
        com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore.class,
        com.dalai.llama.tenant.leadmanagement.audience.AudienceStore.class,
        com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator.class, com.dalai.llama.tenant.leadmanagement.outreach.OutreachSendTimes.class,
        com.dalai.llama.tenant.leadmanagement.service.impl.CreatorEmailIdentityServiceImpl.class, OutreachDbTest.TestConfig.class})
class OutreachDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z"); // a Wednesday

    @TestConfiguration
    @EnableConfigurationProperties({OutreachProperties.class, ShowcaseProperties.class,
            com.dalai.llama.tenant.leadmanagement.config.LeadManagementProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private CreatorEmailSender creatorSender;
    @MockBean private com.dalai.llama.tenant.showcase.inspiration.InspirationPosters posters;
    @MockBean private PlatformMailer platformMailer;
    @MockBean private BillingServiceClient billing;
    @MockBean private com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver domains;
    @Autowired private OutreachService outreach;
    @Autowired private DailyDigestJob digest;
    @Autowired private com.dalai.llama.tenant.leadmanagement.outreach.OutreachDispatchJob dispatchJob;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired private RecipientHasher hasher;
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
        when(domains.check(any())).thenReturn(com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver.MailDomain.ACCEPTS_MAIL);
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
        // A letter from the creator showing the film: who it was for, its format and length, a way to watch it.
        assertThat(mail.getValue().subject()).isEqualTo("A video I made recently: Video riya0000001");
        assertThat(mail.getValue().bodyText()).contains("Hi Asha,", "Hello", "Video riya0000001", "Made for a food & beverage brand",
                "Product ad · 0:30 · Widescreen · Made on Dalai Llama for a paying client", "Watch the film: ", "Riya Motion\ndalaillama.in/c/riya-motion");
        assertThat(mail.getValue().bodyHtml()).contains("/showcase/pubriya0001/poster", "Watch the film", "Request one like this",
                "Request a video from me", "/o/");
        // "Request" opens the brands site's request form for this creator, not a reply.
        assertThat(jdbc.queryForList("SELECT target_url FROM lead_outreach_link", String.class))
                .anyMatch(t -> t.startsWith("https://dalaillama.in/creators?then=request&creator=riya-motion&film=pubriya0001&industry=FOOD_BEVERAGE&ref="));

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
    void aCreatorWithNoMailableFilmYetStillSeesTheTemplateWithASampleCard() {
        UUID neha = tenant(jdbc, "Neha Studio");
        profileService.ensureProfile(neha, "Neha Studio");

        var preview = outreach.preview(neha, new com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewRequest(
                OutreachTemplate.SHOWCASE_WORK, null, null, null, "Asha", null, null));

        assertThat(preview.html()).contains("Your film appears here", "Request a video from me", "See all my work", "Neha Studio", "Hi Asha");
        // Unsaved edits show live in the preview.
        var edited = outreach.preview(neha, new com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewRequest(
                OutreachTemplate.SHOWCASE_WORK, null, null, null, "Asha", "A film idea for {name}", "Loved your last launch."));
        assertThat(edited.subject()).isEqualTo("A film idea for Asha");
        assertThat(edited.html()).contains("Loved your last launch.");
        assertThat(preview.html()).contains(OutreachService.SAMPLE_THUMBNAIL);
    }

    @Test
    void theSameCreatorWaitsTwoWeeksBeforeMailingTheSamePersonAgain() {
        SendResult first = outreach.send(riya, new SendRequest(OutreachTemplate.SHOWCASE_WORK, null, "pubriya0001", null,
                List.of(new Recipient("a@x.example", null, null), new Recipient("A@x.example ", null, null))));
        assertThat(first.results()).extracting(r -> r.outcome()).containsExactly(Outcome.SENT, Outcome.DUPLICATE);

        clock.set(NOW.plus(Duration.ofDays(13)));
        assertThat(send(riya, "pubriya0001", "a@x.example").results().get(0).outcome()).isEqualTo(Outcome.COOLDOWN);
        clock.set(NOW.plus(Duration.ofDays(15)));
        assertThat(send(riya, "pubriya0001", "a@x.example").results().get(0).outcome()).isEqualTo(Outcome.SENT);
    }

    @Test
    void freeMailsRunOutThenAPaidPackIsUsedAndARetriedPurchaseIsNotDoubled() {
        SendResult sent = outreach.send(riya, new SendRequest(OutreachTemplate.CREATOR_PORTFOLIO, null, null, null, List.of(
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
        String token = jdbc.queryForObject("SELECT token FROM lead_outreach_link WHERE target_url LIKE '%/c/%'", String.class);

        assertThat(store.click(token)).get().asString()
                .isEqualTo("https://dalaillama.in/c/riya-motion?film=pubriya0001&industry=FOOD_BEVERAGE&ref=" + token);
        assertThat(jdbc.queryForObject("SELECT click_count FROM lead_outreach_link WHERE token = ?", Integer.class, token)).isEqualTo(1);

        store.purgeBefore(NOW.plus(Duration.ofDays(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_intent WHERE recipient_email IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_delivery WHERE recipient_email IS NOT NULL", Integer.class)).isZero();
        // The cooldown still holds: it keys on the hash.
        assertThat(send(riya, "pubriya0001", "click@x.example").results().get(0).outcome()).isEqualTo(Outcome.COOLDOWN);
    }

    @Test
    void onceTheDailyCapIsReachedCreatorMailStaysQueuedAndGoesOutTomorrow() {
        otherDeliveriesToday(4);
        assertThat(send(riya, "pubriya0001", "fifth@x.example").results().get(0).outcome()).isEqualTo(Outcome.SENT);

        assertThat(send(riya, "pubriya0001", "sixth@x.example").results().get(0).outcome()).isEqualTo(Outcome.DAILY_LIMIT);
        assertThat(jdbc.queryForObject("SELECT status FROM lead_outreach_intent WHERE recipient_email = 'sixth@x.example'",
                String.class)).isEqualTo("QUEUED");
        assertThat(dispatchJob.run().sent()).isZero();
        verify(creatorSender, times(1)).send(any());

        clock.set(NOW.plus(Duration.ofDays(1)));
        assertThat(dispatchJob.run().sent()).isEqualTo(1);
        verify(creatorSender, times(2)).send(any());
        // Charged once, when it was accepted: 2 of the 3 free mails used, none given back or taken again.
        assertThat(outreach.overview(riya).allowance().freeUsedThisWeek()).isEqualTo(2);
    }

    @Test
    void theDigestStopsAtTheDailyCapAndTheRestWaitsForTheNextDigest() {
        send(riya, "pubriya0001", "buyer@hearth.example");
        send(arjun, "pubarjun001", "buyer@hearth.example"); // waits for a digest

        clock.set(NOW.plus(Duration.ofDays(1)));
        otherDeliveriesToday(5);
        assertThat(digest.run().digestsSent()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_outreach_intent WHERE status = 'PENDING'", Integer.class)).isEqualTo(1);

        clock.set(NOW.plus(Duration.ofDays(2)));
        assertThat(digest.run().digestsSent()).isEqualTo(1);
    }

    @Test
    void twoSendsAtOnceCannotBothTakeTheLastPlace() throws Exception {
        otherDeliveriesToday(4);
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 7);
        java.util.concurrent.CountDownLatch firstClaimed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch commitFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transactions.execute(tx -> {
                boolean room = store.dayHasRoom(today, 5);
                store.claimDay("a".repeat(64), today, "DIGEST", null, "a@x.example", "digest");
                firstClaimed.countDown();
                await(commitFirst);
                return room;
            }));
            firstClaimed.await();
            var second = pool.submit(() -> transactions.execute(tx -> store.dayHasRoom(today, 5)));
            Thread.sleep(300);
            assertThat(second.isDone()).isFalse(); // waiting on the day's lock
            commitFirst.countDown();

            assertThat(first.get()).isTrue();
            assertThat(second.get()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void heldMailIsMergedWithEachCreatorsMessageAllTheirFilmsAndARequestButtonForEach() {
        UUID kabir = creator("Kabir Reels", "UCkabir", "kabir000001", "pubkabir001", true);
        film(kabir, "kabir000002", "pubkabir002", "UCkabir", true);
        send(riya, "pubriya0001", "buyer@hearth.example"); // today's mail
        send(arjun, "pubarjun001", "buyer@hearth.example"); // held, with the note "Hello"
        outreach.send(kabir, new SendRequest(OutreachTemplate.CREATOR_PORTFOLIO, null, null, null,
                List.of(new Recipient("buyer@hearth.example", "Asha", null)))); // held, two films

        clock.set(NOW.plus(Duration.ofDays(1)));
        assertThat(digest.run().digestsSent()).isEqualTo(1);

        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(platformMailer).send(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("2 creators on Dalai Llama shared videos with you");
        assertThat(mail.getValue().text()).contains("A CREATOR WROTE TO YOU: Arjun Frames", "Hello",
                "Request a video from Arjun Frames: https://api.dalaillama.in/api/v1/public/outreach/r/",
                "A CREATOR WROTE TO YOU: Kabir Reels", "Video kabir000001", "Video kabir000002", "Request a video from Kabir Reels",
                "Made for a food & beverage brand", "Made on Dalai Llama for a paying client");
        // Every film is shown in full, with its own poster and both buttons; nobody is asked to reply by mail.
        assertThat(mail.getValue().html()).contains("/showcase/pubkabir002/poster", "Ask Kabir Reels for one like this")
                .doesNotContain("https://i/kabir000002.jpg", "mailto:", "Reply to");
        assertThat(mail.getValue().html().split("Watch the film", -1)).hasSize(4);
        assertThat(jdbc.queryForList("SELECT target_url FROM lead_outreach_link", String.class))
                .anyMatch(t -> t.startsWith("https://dalaillama.in/creators?then=request&creator=kabir-reels&industry=FOOD_BEVERAGE&ref="));
        assertThat(mail.getValue().html()).contains("https://api.dalaillama.in/api/v1/public/outreach/o/");
    }

    @Test
    void aCreatorWithNoFilmYetMailsInspirationVideosCreditedToTheirMakers() {
        UUID neha = tenant(jdbc, "Neha Studio");
        profileService.ensureProfile(neha, "Neha Studio");
        jdbc.update("INSERT INTO creator_profile_industry (tenant_id, industry) VALUES (?, 'FOOD_BEVERAGE')", neha);
        UUID juice = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inspiration_item (id, pin_id, title, industry, sort_order, poster_url, poster_width, poster_height, maker_name)
                VALUES (?, '1037376095420175260', 'Healthy juice benefits reel', 'FOOD_BEVERAGE', 0, 'https://i.pinimg.com/564x/juice.jpg', 564, 1002, 'Dadu Fresh')""",
                juice);
        // No picture stored and Pinterest can't give one: left out of mail.
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, sort_order) VALUES (?, '1061653312137891793', 'Mochi launch', 'FOOD_BEVERAGE', 1)",
                UUID.randomUUID());
        when(posters.fetch(any())).thenReturn(java.util.Optional.empty());

        var result = outreach.send(neha, new SendRequest(OutreachTemplate.INSPIRATION, null, null, null,
                List.of(new Recipient("buyer@hearth.example", "Asha", null))));

        assertThat(result.results().get(0).outcome()).isEqualTo(Outcome.SENT);
        ArgumentCaptor<CreatorEmailMessage> mail = ArgumentCaptor.forClass(CreatorEmailMessage.class);
        verify(creatorSender).send(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("Video ideas for your brand");
        assertThat(mail.getValue().bodyText()).contains("videos food & beverage brands are running", "Healthy juice benefits reel",
                "Found on Pinterest · by Dadu Fresh", "An idea for your brand, not made on Dalai Llama", "Watch on Pinterest",
                "Request a video from me").doesNotContain("Mochi launch");
        assertThat(mail.getValue().bodyHtml()).contains("https://i.pinimg.com/564x/juice.jpg", "width=\"300\"");
        assertThat(jdbc.queryForObject("SELECT template FROM lead_outreach_intent", String.class)).isEqualTo("INSPIRATION");
        assertThat(jdbc.queryForList("SELECT target_url FROM lead_outreach_link", String.class)).contains(
                "https://www.pinterest.com/pin/1037376095420175260/")
                .anyMatch(t -> t.startsWith("https://dalaillama.in/creators?then=request&creator=neha-studio&inspiration=1037376095420175260"));
    }

    @Test
    void heldMailThatIsDroppedIsGivenBackToTheCreator() {
        for (String other : List.of("p1@x.example", "p2@x.example", "p3@x.example")) send(arjun, "pubarjun001", other);
        send(riya, "pubriya0001", "buyer@hearth.example");
        when(billing.purchaseAddon(eq(arjun), eq("OUTREACH_MAIL_PACK"), anyString()))
                .thenReturn(new BillingServiceClient.AddonPurchase("OUTREACH_MAIL_PACK", 500, new BigDecimal("499"), "INR", BigDecimal.TEN));
        outreach.buyPack(arjun, "k1");
        assertThat(send(arjun, "pubarjun001", "buyer@hearth.example").results().get(0).outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(jdbc.queryForObject("SELECT remaining FROM lead_mail_pack", Integer.class)).isEqualTo(499);

        clock.set(NOW.plus(Duration.ofDays(8)));
        assertThat(digest.run().expired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remaining FROM lead_mail_pack", Integer.class)).isEqualTo(500);
    }

    @Test
    void aFreeMailHeldForSomeoneWhoUnsubscribesComesBack() {
        send(riya, "pubriya0001", "gone@x.example");
        send(arjun, "pubarjun001", "gone@x.example"); // held
        assertThat(outreach.overview(arjun).allowance().freeUsedThisWeek()).isEqualTo(1);

        outreach.unsubscribe(jdbc.queryForObject("SELECT unsubscribe_token FROM lead_outreach_delivery", String.class));

        assertThat(outreach.overview(arjun).allowance().freeUsedThisWeek()).isZero();
    }

    @Autowired private com.dalai.llama.tenant.leadmanagement.outreach.OutreachSendTimes sendTimes;

    @Test
    void theDigestGoesOutAtTheHourTheBrandOpensMail() {
        // Nothing to learn from yet: the configured default.
        assertThat(sendTimes.platformHour()).isEqualTo(13);
        // buyer@ opened three earlier mails around 20:00 UTC and clicked one at 08:00.
        String buyer = hasher.hash("buyer@hearth.example");
        for (int day = 1; day <= 3; day++) {
            java.time.LocalDate past = java.time.LocalDate.of(2026, 9, day);
            OutreachStore.Delivery d = store.claimDay(buyer, past, "DIGEST", null, "buyer@hearth.example", "digest").orElseThrow();
            store.recordOpen(d.id(), past.atTime(20, 10).toInstant(java.time.ZoneOffset.UTC));
        }
        assertThat(sendTimes.hourFor(buyer, 13)).isEqualTo(20);
        // With too few signals of their own, a recipient gets the platform's hour (learned from everyone).
        assertThat(sendTimes.hourFor(hasher.hash("quiet@x.example"), 13)).isEqualTo(13);
        assertThat(sendTimes.platformHour()).isEqualTo(20);

        send(riya, "pubriya0001", "buyer@hearth.example");
        send(arjun, "pubarjun001", "buyer@hearth.example"); // held
        clock.set(Instant.parse("2026-10-08T13:00:00Z"));
        assertThat(digest.runDue().digestsSent()).isZero();
        clock.set(Instant.parse("2026-10-08T20:00:00Z"));
        assertThat(digest.runDue().digestsSent()).isEqualTo(1);
        // Once a day: later hours don't send again.
        clock.set(Instant.parse("2026-10-08T21:00:00Z"));
        assertThat(digest.runDue().digestsSent()).isZero();
    }

    /** Mail already delivered today to other people. */
    private void otherDeliveriesToday(int count) {
        java.time.LocalDate today = java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
        for (int i = 0; i < count; i++) {
            store.claimDay(UUID.randomUUID().toString().replace("-", "") + "0".repeat(32), today, "DIGEST", null,
                    "other" + i + "@x.example", "digest");
        }
    }

    private SendResult send(UUID tenant, String publicId, String email) {
        return outreach.send(tenant, new SendRequest(OutreachTemplate.SHOWCASE_WORK, null, publicId, "Hello",
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
