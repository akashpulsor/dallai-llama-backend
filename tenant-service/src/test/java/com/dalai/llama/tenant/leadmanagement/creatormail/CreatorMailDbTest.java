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
        CreatorProfileLinks.class, CreatorProfileService.class, HandlePolicy.class,
        com.dalai.llama.tenant.leadmanagement.outreach.MailIdeas.class, com.dalai.llama.tenant.showcase.inspiration.InspirationStore.class,
        OpsMailHistoryStore.class, OpsMailHistoryService.class, OpsMailTrackingController.class, CreatorMailDbTest.TestConfig.class})
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
    @Autowired private OpsMailHistoryService histories;
    @Autowired private OpsMailTrackingController tracking;
    @Autowired private com.dalai.llama.tenant.showcase.config.ShowcaseProperties showcase;
    @MockBean private PlatformMailer mailer;
    @MockBean private com.dalai.llama.tenant.leadmanagement.outreach.OpenAnalytics analytics;
    @MockBean private com.dalai.llama.tenant.showcase.inspiration.InspirationPosters posters;

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
        // A plain letter from a person: no pictures, layout or bulk-mail headers, so Gmail doesn't file it under Promotions.
        assertThat(toAsha.html()).contains("<a href=\"https://dalaillama.in/tips\"").contains("/unsubscribe/")
                .contains("Akash<br>Dalai Llama").doesNotContain("<img").doesNotContain("<table");
        assertThat(toAsha.text()).contains("Unsubscribe and I won't write again: ");
        assertThat(toAsha.headers()).isEmpty();
        assertThat(toAsha.sender()).isEqualTo(new PlatformMailer.Sender("Akash from Dalai Llama", "hello@partner.dalaillama.in"));

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
        assertThat(mail.getValue().subject()).isEqualTo("Hi creator");
        assertThat(mail.getValue().html()).contains("&lt;b&gt;Body&lt;/b&gt;");
        assertThat(service.templates(null).get(0).lastSentAt()).isNull();

        // Its unsubscribe link is real: it resolves to the tester, and the test doesn't use up the template.
        String token = jdbc.queryForObject("SELECT unsubscribe_token FROM ops_creator_mail_delivery WHERE status = 'TEST'", String.class);
        assertThat(mail.getValue().html()).contains("/unsubscribe/" + token);
        assertThat(outreach.recipientOfUnsubscribeToken(token)).contains(hasher.hash("ops@example.com"));
        account("Ops Person", "ops@example.com", "CREATOR", "ACTIVE");
        assertThat(service.send(new SendRequest(template.id(), Audience.ALL, null, null)).recipients()).isEqualTo(3);

        // A test to an address we know by name greets that name.
        clearInvocations(mailer);
        service.send(new SendRequest(template.id(), Audience.TEST, null, "Asha@Example.com"));
        verify(mailer).sendAfterCommit(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("Hi Asha");
    }

    @Test
    void anIdeasTemplateCarriesVideosFromTheInspirationBoard() {
        jdbc.update("""
                INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url, poster_width, poster_height, maker_name)
                VALUES (?, '1234567890', 'Coffee pour in slow motion', 'FOOD_BEVERAGE', 'https://img.example/coffee.jpg', 720, 1280, 'Studio K')""",
                UUID.randomUUID());
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry) VALUES (?, '1234567891', 'No picture yet', 'FASHION')",
                UUID.randomUUID());
        Template ideas = service.create(new TemplateRequest("Ideas", "{name}, ideas", "Hi {name}, a few ideas:", "Make a video",
                "https://creator.dalaillama.in", 3, null));
        assertThat(ideas.ideaVideos()).isEqualTo(3);

        service.send(new SendRequest(ideas.id(), Audience.ALL, null, null));
        service.dispatch();
        PlatformMailer.Mail mail = sent().get(0);
        // Listed as titles with one link to watch them, no pictures.
        assertThat(mail.html()).contains("<li").contains("Coffee pour in slow motion (by Studio K)")
                .contains(">watch them on the inspiration board</a>").contains("/ops/c/")
                .doesNotContain("https://img.example/coffee.jpg").doesNotContain("No picture yet");
        // The tracked link goes on to the board, on the video's industry.
        assertThat(jdbc.queryForList("SELECT url FROM ops_mail_delivery_link", String.class))
                .anySatisfy(url -> assertThat(url).endsWith("/inspiration?industry=FOOD_BEVERAGE"));
        assertThat(mail.text()).contains("- Coffee pour in slow motion (by Studio K)").contains("You can watch them here: https://");

        // The preview shows the same list.
        assertThat(service.preview(new CreatorMailService.PreviewRequest("Ideas", "Hi", null, null, 2)).html()).contains("Coffee pour in slow motion");
    }

    @Test
    void aMissingNameDropsOutOfTheSubjectCleanly() {
        assertThat(CreatorMailService.fill("{name}, brands are searching for you", null)).isEqualTo("Brands are searching for you");
        assertThat(CreatorMailService.fill("Hi {name},", null)).isEqualTo("Hi creator,");
        assertThat(CreatorMailService.fill("{name}, join", "Asha")).isEqualTo("Asha, join");
    }

    @Test
    void withNoNameOnFileTheFirstNameComesFromTheAddressWhenItPlainlyHasOne() {
        assertThat(CreatorMailService.nameFromEmail("asha.rao@gmail.com")).isEqualTo("Asha");
        assertThat(CreatorMailService.nameFromEmail("ravi_92@gmail.com")).isEqualTo("Ravi");
        assertThat(CreatorMailService.nameFromEmail("neha+yt@gmail.com")).isEqualTo("Neha");
        assertThat(CreatorMailService.nameFromEmail("info@studio.in")).isNull();
        assertThat(CreatorMailService.nameFromEmail("hello.films@studio.in")).isNull();
        assertThat(CreatorMailService.nameFromEmail("akashtripathi.2801@gmail.com")).isNull();
        assertThat(CreatorMailService.nameFromEmail("ab@x.in")).isNull();

        // A contact name that is really an address or a handle is read like one.
        assertThat(CreatorMailService.firstName("ASHA RAO")).isEqualTo("Asha");
        assertThat(CreatorMailService.firstName("asha_rao92")).isEqualTo("Asha");
        assertThat(CreatorMailService.firstName("akashtripathi.2801@gmail.com")).isNull();
        assertThat(CreatorMailService.firstName("x")).isNull();

        Template template = service.create(new TemplateRequest("Hi", "Hi {name}", "Hello {name}", null));
        jdbc.update("INSERT INTO ops_creator_lead (id, email) VALUES (?, 'meera.k@example.com')", UUID.randomUUID());
        service.send(new SendRequest(template.id(), Audience.LEADS, null, null));
        service.dispatch();
        assertThat(sent().get(0).subject()).isEqualTo("Hi Meera");
    }

    @Test
    void aTemplateCanEndWithAButtonAndThePreviewShowsTheRealMail() {
        Template template = service.create(new TemplateRequest("Join", "{name}, join", "Hello {name}", "Join free, {name}",
                "https://creator.dalaillama.in", null));
        assertThat(template.ctaUrl()).isEqualTo("https://creator.dalaillama.in");

        service.send(new SendRequest(template.id(), Audience.TEST, null, "ops@example.com"));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).sendAfterCommit(mail.capture());
        assertThat(mail.getValue().html()).contains("href=\"https://creator.dalaillama.in\"").contains("Join free, creator");
        assertThat(mail.getValue().text()).contains("Join free, creator: https://creator.dalaillama.in");

        CreatorMailService.Preview preview = service.preview(new CreatorMailService.PreviewRequest("{name}, ideas", "Hi {name}", "Go", "https://x.example"));
        assertThat(preview.subject()).isEqualTo("Asha, ideas");
        assertThat(preview.html()).contains("Hi Asha").contains("href=\"https://x.example\"");

        assertThatThrownBy(() -> service.create(new TemplateRequest("Half", "S", "B", "Label only", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTestToAnAddressWithSeveralAccountsGreetsTheCreatorsRealName() {
        // The same person also has a brand account whose "name" is their address.
        account("dev.k@example.com", "dev.k@example.com", "BRAND", "ACTIVE");
        account("Devika K", "dev.k@example.com", "CREATOR", "ACTIVE");
        Template template = service.create(new TemplateRequest("Hi", "{name}, hello", "Hi {name},", null));

        service.send(new SendRequest(template.id(), Audience.TEST, null, "dev.k@example.com"));

        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).sendAfterCommit(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("Devika, hello");
    }

    @Test
    void anActiveCreatorWhoseNameIsTheirAddressStillGetsTheMailWithAFallbackGreeting() {
        account("akashtripathi.2801@gmail.com", "akashtripathi.2801@gmail.com", "CREATOR", "ACTIVE");
        Template template = service.create(new TemplateRequest("Hi", "{name}, hello", "Hi {name},", null));

        assertThat(service.send(new SendRequest(template.id(), Audience.ALL, null, null)).recipients()).isEqualTo(3);
        service.dispatch();
        PlatformMailer.Mail toAkash = sent().stream().filter(m -> m.to().equals("akashtripathi.2801@gmail.com")).findFirst().orElseThrow();
        assertThat(toAkash.subject()).isEqualTo("Hello");
        assertThat(toAkash.text()).startsWith("Hi creator,");
    }

    @Test
    void ideasComeFromTheTemplatesIndustryOrARandomOneEachMail() {
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, ?, ?, 'TECH', 'https://img.example/t.jpg')",
                    UUID.randomUUID(), "77700000" + i, "Tech idea " + i);
            jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, ?, ?, 'TRAVEL', 'https://img.example/v.jpg')",
                    UUID.randomUUID(), "88800000" + i, "Travel idea " + i);
        }
        Template tech = service.create(new TemplateRequest("Tech", "Ideas", "Hi", null, null, 2,
                com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.TECH, null, null));
        assertThat(tech.ideaIndustry()).isEqualTo(com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.TECH);
        for (int i = 0; i < 5; i++) {
            String html = service.preview(new CreatorMailService.PreviewRequest("Ideas", "Hi", null, null, 2,
                    com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.TECH, null)).html();
            assertThat(html).contains("Tech idea").doesNotContain("Travel idea");
        }
        // Random: each mail's videos are all from one industry, and over a few mails both industries show up.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 40 && seen.size() < 2; i++) {
            String html = service.preview(new CreatorMailService.PreviewRequest("Ideas", "Hi", null, null, 2)).html();
            assertThat(html.contains("Tech idea") && html.contains("Travel idea")).isFalse();
            seen.add(html.contains("Tech idea") ? "TECH" : "TRAVEL");
        }
        assertThat(seen).containsExactlyInAnyOrder("TECH", "TRAVEL");
    }

    @Test
    void aStyledTemplateSendsTheDesignedMailWithPictureCardsAndAButton() {
        jdbc.update("""
                INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url, poster_width, poster_height, maker_name)
                VALUES (?, '1234567892', 'Phone unboxing in one take', 'TECH', 'https://img.example/phone.jpg', 720, 1280, 'Ckim')""",
                UUID.randomUUID());
        Template styled = service.create(new TemplateRequest("Styled", "{name}, ideas", "Hi {name}", "Make a video",
                "https://creator.dalaillama.in", 1, CreatorMailStore.MailStyle.STYLED, null));
        assertThat(styled.style()).isEqualTo(CreatorMailStore.MailStyle.STYLED);

        service.send(new SendRequest(styled.id(), Audience.TEST, null, "ops@example.com"));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).sendAfterCommit(mail.capture());
        assertThat(mail.getValue().html()).contains("FOR CREATORS").contains("https://img.example/phone.jpg").contains("Watch the video")
                .contains("By Ckim").contains("href=\"https://creator.dalaillama.in\"");
        assertThat(mail.getValue().headers()).containsKey("List-Unsubscribe");
        assertThat(mail.getValue().sender().name()).isEqualTo("Akash from Dalai Llama");
        // Templates are plain unless chosen otherwise.
        assertThat(service.create(new TemplateRequest("Plain", "S", "B", null)).style()).isEqualTo(CreatorMailStore.MailStyle.PLAIN);
    }

    @Test
    void switchedOffTemplateIsNotSent() {
        Template template = service.create(new TemplateRequest("Old", "Old", "Body", null));
        service.update(template.id(), new TemplateRequest("Old", "Old", "Body", false));

        assertThatThrownBy(() -> service.send(new SendRequest(template.id(), Audience.ALL, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aBrandTemplateGoesToOneContactPerMatchingBrand_withItsNameCompanyAndIndustryVideos() {
        jdbc.update("DELETE FROM lead_company WHERE provider = 'TEST'");
        UUID hearth = brand("hearth-test.in", "Hearth Foods", "FOOD_BEVERAGE", "IN", "SMALL");
        contact(hearth, "asha@hearth-test.in", "Asha Rao", 90);
        contact(hearth, "hello@hearth-test.in", null, 10);
        contact(brand("bigco-test.com", "BigCo", "FOOD_BEVERAGE", "IN", "LARGE"), "ceo@bigco-test.com", "Ravi", 90);
        contact(brand("gadget-test.in", "Gadget", "TECH", "IN", "SMALL"), "team@gadget-test.in", null, 50);
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, '99900001', 'Food idea', 'FOOD_BEVERAGE', 'https://img.example/f.jpg')",
                UUID.randomUUID());
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, '99900002', 'Tech idea', 'TECH', 'https://img.example/t.jpg')",
                UUID.randomUUID());
        Template template = service.create(new TemplateRequest("Brands", "{name}, ideas for {company}", "Hi {name},", null, null, 1, null, null,
                CreatorMailStore.MailFor.BRANDS, null));

        // Brand templates are listed apart from creator ones.
        assertThat(service.templates(CreatorMailStore.MailFor.BRANDS)).extracting(Template::id).contains(template.id());
        assertThat(service.templates(null)).extracting(Template::id).doesNotContain(template.id());

        var smallFoodInIndia = new CreatorMailStore.BrandFilter(List.of(com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.FOOD_BEVERAGE),
                List.of(com.dalai.llama.tenant.leadmanagement.audience.CompanySize.SMALL), List.of("in"));
        assertThat(service.brandAudience(smallFoodInIndia).brands()).isEqualTo(1);
        assertThat(service.brandAudience(new CreatorMailStore.BrandFilter(List.of(), List.of(), List.of())).brands()).isEqualTo(3);
        // A brand template doesn't go to creators.
        assertThatThrownBy(() -> service.send(new SendRequest(template.id(), Audience.ALL, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        SendResult result = service.send(new SendRequest(template.id(), Audience.BRANDS, null, null, smallFoodInIndia));
        assertThat(result.recipients()).isEqualTo(1); // one contact per brand: its best
        service.dispatch();
        PlatformMailer.Mail mail = sent().get(0);
        assertThat(mail.to()).isEqualTo("asha@hearth-test.in");
        assertThat(mail.subject()).isEqualTo("Asha, ideas for Hearth Foods");
        assertThat(mail.html()).contains("Food idea").doesNotContain("Tech idea"); // the brand's own industry
    }

    @Test
    void aBrandPreviewShowsTheVideosOfTheBrandsPicked_notAlwaysFood() {
        jdbc.update("DELETE FROM lead_company WHERE provider = 'TEST'");
        jdbc.update("DELETE FROM inspiration_item");
        contact(brand("loom-test.in", "Loom Studio", "FASHION", "IN", "SMALL"), "hi@loom-test.in", "Meera", 90);
        contact(brand("hearth-test.in", "Hearth Foods", "FOOD_BEVERAGE", "IN", "SMALL"), "asha@hearth-test.in", "Asha", 90);
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, '77700001', 'Food idea', 'FOOD_BEVERAGE', 'https://img.example/f.jpg')",
                UUID.randomUUID());
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, '77700002', 'Fashion idea', 'FASHION', 'https://img.example/c.jpg')",
                UUID.randomUUID());
        var fashion = new CreatorMailStore.BrandFilter(List.of(com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.FASHION), List.of(), List.of());

        CreatorMailService.Preview preview = service.preview(new CreatorMailService.PreviewRequest("Ideas for {company}", "Hi {name}", null, null, 1,
                null, null, CreatorMailStore.MailFor.BRANDS, fashion));

        assertThat(preview.to()).isEqualTo("Loom Studio");
        assertThat(preview.subject()).isEqualTo("Ideas for Loom Studio");
        assertThat(preview.html()).contains("Fashion idea").doesNotContain("Food idea");
        // A preview is not a send: nothing is remembered as mailed to the brand.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_mail_idea_sent WHERE email = 'hi@loom-test.in'", Integer.class)).isZero();
    }

    @Test
    void aBrandGetsVideosAboutWhatItSells_andNewOnesInItsNextMail() {
        jdbc.update("DELETE FROM lead_company WHERE provider = 'TEST'");
        jdbc.update("DELETE FROM inspiration_item");
        // A pet brand filed under e-commerce: its videos are the pet ones (filed under other), not e-commerce ones.
        UUID muttley = brand("muttley-test.in", "Muttley Crew", "ECOMMERCE", "IN", "SMALL");
        jdbc.update("UPDATE lead_company SET category = 'Pet' WHERE id = ?", muttley);
        contact(muttley, "woof@muttley-test.in", "Riya", 90);
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, ?, ?, 'OTHER', 'https://img.example/p.jpg')",
                    UUID.randomUUID(), "55500000" + i, "Pet product " + i);
            jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, ?, ?, 'ECOMMERCE', 'https://img.example/e.jpg')",
                    UUID.randomUUID(), "66600000" + i, "Online store hook " + i);
        }
        var everyone = new CreatorMailStore.BrandFilter(List.of(), List.of(), List.of());
        Template first = service.create(new TemplateRequest("One", "Ideas", "Hi", null, null, 2, null, null, CreatorMailStore.MailFor.BRANDS, null));
        Template second = service.create(new TemplateRequest("Two", "More ideas", "Hi", null, null, 2, null, null, CreatorMailStore.MailFor.BRANDS, null));

        service.send(new SendRequest(first.id(), Audience.BRANDS, null, null, everyone));
        service.dispatch();
        service.send(new SendRequest(second.id(), Audience.BRANDS, null, null, everyone));
        service.dispatch();

        List<PlatformMailer.Mail> mails = sent();
        java.util.regex.Pattern pet = java.util.regex.Pattern.compile("Pet product \\d");
        List<String> firstVideos = pet.matcher(mails.get(0).html()).results().map(m -> m.group()).toList();
        List<String> secondVideos = pet.matcher(mails.get(1).html()).results().map(m -> m.group()).toList();
        assertThat(firstVideos).hasSize(2);
        assertThat(secondVideos).hasSize(2).doesNotContainAnyElementsOf(firstVideos); // new ones the second time
        assertThat(mails.get(0).html() + mails.get(1).html()).doesNotContain("Online store hook");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_mail_idea_sent WHERE email = 'woof@muttley-test.in'", Integer.class)).isEqualTo(4);
    }

    @Test
    void aBrandsHistoryShowsWhatItWasSentWhatItClickedAndTheRequestItSentAfter() {
        jdbc.update("DELETE FROM lead_brand_inquiry WHERE brand_contact_id IN (SELECT id FROM lead_brand_contact WHERE email = 'hi@stride-test.in')");
        jdbc.update("DELETE FROM lead_brand_contact WHERE email = 'hi@stride-test.in'");
        jdbc.update("DELETE FROM lead_company WHERE provider = 'TEST'");
        jdbc.update("DELETE FROM inspiration_item");
        UUID stride = brand("stride-test.in", "Stride", "FASHION", "IN", "SMALL");
        jdbc.update("UPDATE lead_company SET category = 'Footwear & travel' WHERE id = ?", stride);
        contact(stride, "hi@stride-test.in", "Meera", 90);
        UUID sneaker = UUID.randomUUID();
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, '88800001', 'Retro sneaker reveal', 'FASHION', 'https://img.example/s.jpg')",
                sneaker);
        Template template = service.create(new TemplateRequest("Shoes", "Ideas for {company}", "Hi {name},", "Request a video", "https://brands.dalaillama.in/request",
                1, null, CreatorMailStore.MailStyle.STYLED, CreatorMailStore.MailFor.BRANDS, null));

        service.send(new SendRequest(template.id(), Audience.BRANDS, null, null, new CreatorMailStore.BrandFilter(List.of(), List.of(), List.of())));
        service.dispatch();

        UUID delivery = jdbc.queryForObject("SELECT id FROM ops_creator_mail_delivery WHERE email = 'hi@stride-test.in'", UUID.class);
        String html = sent().get(0).html();
        // Its links and open pixel are tracked; the button still leads to the request page.
        assertThat(html).contains("/ops/c/" + delivery + "/0").contains("/ops/c/" + delivery + "/1").contains("/ops/o/" + delivery + ".gif")
                .doesNotContain("href=\"https://brands.dalaillama.in/request\"");
        // Opening it is recorded and sent to Google Analytics as an ops brand mail open, once as the first.
        tracking.open(delivery);
        tracking.open(delivery);
        org.mockito.Mockito.verify(analytics).opsOpened(org.mockito.ArgumentMatchers.eq("BRANDS"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("Shoes"), org.mockito.ArgumentMatchers.eq(true));
        org.mockito.Mockito.verify(analytics).opsOpened(org.mockito.ArgumentMatchers.eq("BRANDS"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("Shoes"), org.mockito.ArgumentMatchers.eq(false));
        tracking.open(UUID.randomUUID()); // not one of ours: no event
        org.mockito.Mockito.verifyNoMoreInteractions(analytics);
        org.springframework.http.ResponseEntity<Void> click = tracking.click(delivery, 1);
        assertThat(click.getHeaders().getLocation()).hasToString("https://brands.dalaillama.in/request");
        assertThat(tracking.click(delivery, 9).getHeaders().getLocation()).hasToString(showcase.publicBaseUrl()); // not one of its links

        UUID contactId = UUID.randomUUID();
        jdbc.update("INSERT INTO lead_brand_contact (id, email, source) VALUES (?, 'hi@stride-test.in', 'SIGNUP')", contactId);
        jdbc.update("INSERT INTO lead_brand_inquiry (id, brand_contact_id, message, status, source) VALUES (?, ?, 'Shoe ad', 'NEW', 'LANDING')",
                UUID.randomUUID(), contactId);
        // The video is later taken off the board; the mail still shows it was sent.
        jdbc.update("DELETE FROM inspiration_item WHERE id = ?", sneaker);

        OpsMailHistoryService.BrandHistory history = histories.brand("Hi@Stride-Test.in");
        assertThat(history.mails()).hasSize(1);
        OpsMailHistoryService.MailEntry mail = history.mails().get(0);
        assertThat(mail.mail().templateName()).isEqualTo("Shoes");
        assertThat(mail.mail().openedAt()).isNotNull();
        assertThat(mail.mail().videosFit()).isTrue(); // a footwear brand got a sneaker video
        assertThat(mail.videos()).extracting(OpsMailHistoryStore.ShownVideo::title, OpsMailHistoryStore.ShownVideo::onBoard)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Retro sneaker reveal", false));
        assertThat(mail.links()).extracting(OpsMailHistoryStore.MailLink::label, OpsMailHistoryStore.MailLink::clicks)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Retro sneaker reveal", 0), org.assertj.core.groups.Tuple.tuple("Request a video", 1));
        assertThat(history.requests()).hasSize(1);
        assertThat(histories.brands("stride")).singleElement()
                .satisfies(b -> assertThat(List.of(b.mails(), b.opened(), b.clicked(), b.requests())).containsExactly(1, 1, 1, 1));
        assertThat(histories.templateStats()).singleElement()
                .satisfies(t -> assertThat(List.of(t.sent(), t.opened(), t.clicked(), t.requested())).containsExactly(1, 1, 1, 1));

        // Ops read the mail as it went, filled in and with its links, but with no pixel to count their reading as an open.
        assertThat(mail.mail().subject()).isEqualTo("Ideas for Stride");
        assertThat(mail.mail().html()).isEqualTo(html.replaceAll("<img src=\"[^\"]*/ops/o/[^>]*>", ""));
        assertThat(mail.mail().html()).contains("Hi Meera,").contains("/ops/c/" + delivery + "/0").doesNotContain("/ops/o/");

        // A later change to the template doesn't change what the brand was sent.
        jdbc.update("UPDATE ops_creator_mail_template SET subject = 'Changed', body = 'Changed' WHERE id = ?", template.id());
        assertThat(histories.brand("hi@stride-test.in").mails().get(0).mail().subject()).isEqualTo("Ideas for Stride");

        // A mail from before copies were kept: no page, but the subject is still as the brand read it.
        jdbc.update("UPDATE ops_creator_mail_delivery SET sent_subject = NULL, sent_html = NULL WHERE id = ?", delivery);
        jdbc.update("UPDATE ops_creator_mail_send SET subject = '{name}, ideas for {company}' WHERE template_id = ?", template.id());
        OpsMailHistoryStore.SentMail older = histories.brand("hi@stride-test.in").mails().get(0).mail();
        assertThat(older.subject()).isEqualTo("Meera, ideas for Stride");
        assertThat(older.html()).isNull();
    }

    @Test
    void brandMailWithNoVideoAboutWhatTheBrandSellsIsMailedToOpsOnceADay() {
        jdbc.update("DELETE FROM lead_company WHERE provider = 'TEST'");
        jdbc.update("DELETE FROM inspiration_item");
        UUID carry = brand("carry-test.in", "Carry Co", "FASHION", "IN", "SMALL");
        jdbc.update("UPDATE lead_company SET category = 'handbags' WHERE id = ?", carry);
        contact(carry, "hi@carry-test.in", "Isha", 90);
        jdbc.update("INSERT INTO inspiration_item (id, pin_id, title, industry, poster_url) VALUES (?, '88800002', 'Saree drape reel', 'FASHION', 'https://img.example/d.jpg')",
                UUID.randomUUID());
        Template template = service.create(new TemplateRequest("Bags", "Ideas", "Hi", null, null, 1, null, null, CreatorMailStore.MailFor.BRANDS, null));
        service.send(new SendRequest(template.id(), Audience.BRANDS, null, null, new CreatorMailStore.BrandFilter(List.of(), List.of(), List.of())));
        service.dispatch();

        // The bag brand got the fashion video, its industry's; ops are told bags ran short.
        assertThat(sent().get(0).html()).contains("Saree drape reel");
        assertThat(histories.shortfalls()).singleElement().satisfies(s -> {
            assertThat(s.theme()).isEqualTo("travelgear");
            assertThat(s.brands()).containsExactly("Carry Co");
        });
        assertThat(histories.coverage()).anySatisfy(c -> assertThat(List.of(c.theme(), c.brands(), c.videos())).containsExactly("travelgear", 1, 0));
        clearInvocations(mailer);
        histories.mailShortfalls();
        PlatformMailer.Mail alert = sent().get(0);
        assertThat(alert.subject()).contains("travelgear");
        assertThat(alert.text()).contains("travelgear: 1 brand (Carry Co)");
    }

    private UUID brand(String domain, String name, String industry, String country, String size) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lead_company (id, domain, name, industry, country_code, provider, size_band) VALUES (?, ?, ?, ?, ?, 'TEST', ?)",
                id, domain, name, industry, country, size);
        return id;
    }

    private void contact(UUID company, String email, String name, int confidence) {
        UUID point = jdbc.queryForObject("""
                INSERT INTO lead_contact_point (id, kind, value) VALUES (?, 'EMAIL', ?)
                ON CONFLICT (kind, value) DO UPDATE SET last_seen_at = NOW() RETURNING id""", UUID.class, UUID.randomUUID(), email);
        jdbc.update("INSERT INTO lead_company_contact (company_id, contact_point_id, full_name, confidence) VALUES (?, ?, ?, ?)",
                company, point, name, confidence);
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
