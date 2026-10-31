package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.SendRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.SendResult;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailService.TemplateRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Audience;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.service.CreatorProfileLinks;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ops mail to creators on a real Postgres (V58, V60): who each audience reaches (active creators,
 * picked ones, those whose profile has no video, uploaded creator leads not on the platform), {name}
 * filled, the queue sending within the day's cap, a template reaching an address once, and an
 * unsubscribe stopping everything after. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "outreach.hash-pepper=pepper",
        "creator-mail.daily-cap=3", "creator-mail.per-run=10"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CreatorMailService.class, CreatorMailStore.class, MailRenderer.class, RecipientHasher.class, OutreachStore.class,
        CreatorProfileLinks.class, CreatorProfileService.class, HandlePolicy.class, CreatorMailDbTest.TestConfig.class})
class CreatorMailDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @TestConfiguration
    @org.springframework.boot.context.properties.EnableConfigurationProperties({
            com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties.class,
            com.dalai.llama.tenant.showcase.config.ShowcaseProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired private CreatorMailService service;
    @Autowired private OutreachStore outreach;
    @Autowired private RecipientHasher hasher;
    @Autowired private CreatorProfileService profiles;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private PlatformMailer mailer;

    private UUID asha;
    private UUID ravi;

    @BeforeEach
    void seed() {
        wipe(jdbc);
        jdbc.update("DELETE FROM ops_creator_mail_delivery");
        jdbc.update("DELETE FROM ops_creator_mail_send");
        jdbc.update("DELETE FROM ops_creator_mail_template");
        jdbc.update("DELETE FROM ops_creator_lead");
        // Earlier tests' accounts stop being active creators (other tables point at tenants).
        jdbc.update("UPDATE tenants SET status = 'CREATED' WHERE status = 'ACTIVE'");
        clearInvocations(mailer);
        when(mailer.send(any())).thenReturn(true);
        asha = account("Asha Rao", "asha@example.com", "CREATOR", "ACTIVE");
        ravi = account("Ravi", "ravi@example.com", "CREATOR", "ACTIVE");
        account("Brand Owner", "brand@example.com", "BRAND", "ACTIVE");
        account("Pending Creator", "pending@example.com", "CREATOR", "CREATED");
        // Asha has a video on her profile; Ravi has a profile with none.
        profiles.ensureProfile(asha, "Asha Rao");
        profiles.ensureProfile(ravi, "Ravi");
        liveVideo(asha);
    }

    @Test
    void everyActiveCreatorGetsItFromTheQueueWithTheirFirstNameAndAnUnsubscribeLink() {
        Template template = service.create(new TemplateRequest("Tips", "Hi {name}, a tip", "Hello {name},\n\nSee https://dalaillama.in/tips.", null));

        SendResult result = service.send(new SendRequest(template.id(), Audience.ALL, null, null));

        assertThat(result.audience()).isEqualTo(Audience.ALL);
        assertThat(result.recipients()).isEqualTo(2);
        verify(mailer, never()).send(any());
        assertThat(service.dispatch()).isEqualTo(2);
        List<PlatformMailer.Mail> sent = sent();
        assertThat(sent).extracting(PlatformMailer.Mail::to).containsExactlyInAnyOrder("asha@example.com", "ravi@example.com");
        PlatformMailer.Mail toAsha = sent.stream().filter(m -> m.to().equals("asha@example.com")).findFirst().orElseThrow();
        assertThat(toAsha.subject()).isEqualTo("Hi Asha, a tip");
        assertThat(toAsha.html()).contains("<a href=\"https://dalaillama.in/tips\"").contains("FOR CREATORS").contains("/unsubscribe/");
        assertThat(toAsha.headers()).containsKey("List-Unsubscribe");

        // Sending the same template again reaches nobody twice.
        SendResult again = service.send(new SendRequest(template.id(), Audience.ALL, null, null));
        assertThat(again.recipients()).isZero();
        assertThat(again.alreadyHad()).isEqualTo(2);
    }

    @Test
    void noVideoReachesOnlyCreatorsWhoseProfileShowsNone() {
        Template template = service.create(new TemplateRequest("Profile", "{name}, add a video", "Body", null));

        assertThat(service.creators()).filteredOn(c -> c.tenantId().equals(asha)).singleElement()
                .satisfies(c -> {
                    assertThat(c.liveVideos()).isEqualTo(1);
                    assertThat(c.publicProfileUrl()).contains("/c/");
                });
        assertThat(service.send(new SendRequest(template.id(), Audience.NO_VIDEO, null, null)).recipients()).isEqualTo(1);
        service.dispatch();
        assertThat(sent()).extracting(PlatformMailer.Mail::to).containsExactly("ravi@example.com");
    }

    @Test
    void creatorLeadsAreImportedAndThoseAlreadyOnThePlatformAreLeftOut() {
        var imported = service.importLeads(("""
                name,email,website
                Meera,meera@yt.example,https://youtube.com/@meera
                Ravi Again,RAVI@example.com,
                No Email,,
                Meera Dup,meera@yt.example,
                """).getBytes(StandardCharsets.UTF_8));
        assertThat(imported.added()).isEqualTo(2);
        assertThat(imported.alreadyKnown()).isEqualTo(1);
        assertThat(imported.rejected()).isEqualTo(1);

        Template invite = service.create(new TemplateRequest("Invite", "{name}, join us", "Body", null));
        assertThat(service.send(new SendRequest(invite.id(), Audience.LEADS, null, null)).recipients())
                .as("ravi@example.com already has an account").isEqualTo(1);
        service.dispatch();
        PlatformMailer.Mail mail = sent().get(0);
        assertThat(mail.to()).isEqualTo("meera@yt.example");
        assertThat(mail.subject()).isEqualTo("Meera, join us");
    }

    @Test
    void theDailyCapHoldsTheRestForTomorrowAndAnUnsubscribeSkipsThem() {
        service.importLeads("email\na@x.example\nb@x.example\nc@x.example\nd@x.example\n".getBytes(StandardCharsets.UTF_8));
        Template invite = service.create(new TemplateRequest("Invite", "Join", "Body", null));
        service.send(new SendRequest(invite.id(), Audience.LEADS, null, null));

        assertThat(service.dispatch()).as("cap of 3 a day").isEqualTo(3);
        assertThat(service.dispatch()).isZero();
        assertThat(service.overview().queued()).isEqualTo(1);

        // The one still waiting unsubscribes (through its mail's token) before its turn: skipped, never sent.
        String waiting = jdbc.queryForObject("SELECT email FROM ops_creator_mail_delivery WHERE status = 'QUEUED'", String.class);
        String token = jdbc.queryForObject("SELECT unsubscribe_token FROM ops_creator_mail_delivery WHERE email = ?", String.class, waiting);
        assertThat(outreach.recipientOfUnsubscribeToken(token)).contains(hasher.hash(waiting));
        outreach.suppress(hasher.hash(waiting), "UNSUBSCRIBED");
        jdbc.update("UPDATE ops_creator_mail_delivery SET sent_at = sent_at - INTERVAL '1 day' WHERE status = 'SENT'");
        assertThat(service.dispatch()).isZero();
        assertThat(service.overview().queued()).isZero();
    }

    @Test
    void testSendGoesToOneAddressStraightAway() {
        Template template = service.create(new TemplateRequest("News", "Hi {name}", "<b>Body</b>", null));

        service.send(new SendRequest(template.id(), Audience.TEST, null, "ops@example.com"));

        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).sendAfterCommit(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("ops@example.com");
        assertThat(mail.getValue().subject()).isEqualTo("Hi there");
        assertThat(mail.getValue().html()).contains("&lt;b&gt;Body&lt;/b&gt;");
        assertThat(service.templates().get(0).lastSentAt()).isNull();
    }

    @Test
    void switchedOffTemplateIsNotSent() {
        Template template = service.create(new TemplateRequest("Old", "Old", "Body", null));
        service.update(template.id(), new TemplateRequest("Old", "Old", "Body", false));

        assertThatThrownBy(() -> service.send(new SendRequest(template.id(), Audience.ALL, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    private List<PlatformMailer.Mail> sent() {
        ArgumentCaptor<PlatformMailer.Mail> mails = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).send(mails.capture());
        return mails.getAllValues();
    }

    private void liveVideo(UUID tenant) {
        String channel = "UC" + tenant.toString().substring(0, 8);
        String videoId = "v" + tenant.toString().substring(0, 8);
        verifiedChannel(jdbc, tenant, channel, NOW);
        video(jdbc, videoId, channel, 1280, 720, NOW);
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, industry, format, status,
                    rights_confirmed_at, published_at)
                VALUES (?, ?, ?, ?, 'EXTERNAL', 'FOOD_BEVERAGE', 'PRODUCT_AD', 'LIVE', ?, ?)""",
                UUID.randomUUID(), videoId.substring(0, 8), tenant, videoId, at, at);
    }

    private UUID account(String name, String email, String type, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, primary_contact_name, primary_contact_email, account_type, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", id, name, "t-" + id.toString().substring(0, 8), name, email, type, status);
        return id;
    }
}
