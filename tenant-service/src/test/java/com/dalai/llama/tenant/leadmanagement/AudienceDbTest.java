package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.ImportReport;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.LeadView;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceService;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator;
import com.dalai.llama.tenant.leadmanagement.audience.LeadImportService;
import com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver;
import com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver.MailDomain;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailMessage;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.email.EmailSendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateService;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore;
import com.dalai.llama.tenant.leadmanagement.outreach.IntentDispatcher;
import com.dalai.llama.tenant.leadmanagement.outreach.MailAllowance;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachAnalyticsService;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachAnalyticsStore;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachComposer;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDispatchJob;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.AudienceSendRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.AudienceSendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Outcome;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Recipient;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendRequest;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase E on a real Postgres (rules 21–28): CSV upload into de-duplicated leads, contact point
 * validation and what the creator is shown, audience sends through the dispatcher, creator
 * templates, click verification and analytics. DNS, SMTP and billing are the only fakes. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "outreach.hash-pepper=pepper"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({LeadImportService.class, AudienceService.class, AudienceStore.class, ContactPointValidator.class, RecipientHasher.class,
        OutreachService.class, OutreachStore.class, MailAllowance.class, MailableFilms.class, OutreachComposer.class,
        OutreachRenderer.class, IntentDispatcher.class, EmailTemplateService.class, EmailTemplateStore.class,
        OutreachDispatchJob.class, OutreachAnalyticsService.class, OutreachAnalyticsStore.class, CreatorProfileService.class,
        HandlePolicy.class, AudienceDbTest.TestConfig.class})
class AudienceDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({OutreachProperties.class, ShowcaseProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private CreatorEmailSender creatorSender;
    @MockBean private BillingServiceClient billing;
    @MockBean private MailDomainResolver domains;
    @Autowired private LeadImportService imports;
    @Autowired private AudienceService audiences;
    @Autowired private ContactPointValidator validator;
    @Autowired private OutreachService outreach;
    @Autowired private OutreachDispatchJob dispatch;
    @Autowired private EmailTemplateService templates;
    @Autowired private OutreachAnalyticsService analytics;
    @Autowired private OutreachStore store;
    @Autowired private RecipientHasher hasher;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID riya;
    private UUID list;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        riya = tenant(jdbc, "Riya Motion");
        profileService.ensureProfile(riya, "Riya Motion");
        verifiedChannel(jdbc, riya, "UCriya", NOW);
        video(jdbc, "riya0000001", "UCriya", 1280, 720, NOW);
        Timestamp at = Timestamp.from(NOW.minus(Duration.ofDays(2)));
        jdbc.update("""
                INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, source_project_id, platform_proof,
                    channel, client_locked_at, funded_verified_at, marketing_consent_at, industry, format, status,
                    rights_confirmed_at, published_at)
                VALUES (?, 'pubriya0001', ?, 'riya0000001', 'PLATFORM', ?, 'LINK_MATCH', 'CREATOR', ?, ?, ?, 'FOOD_BEVERAGE',
                    'PRODUCT_AD', 'LIVE', ?, ?)""", UUID.randomUUID(), riya, UUID.randomUUID(), at, at, at, at, at);
        list = audiences.create(riya, "Food brands").id();
        when(creatorSender.send(any())).thenReturn(EmailSendResult.accepted(null));
        when(domains.check(anyString())).thenAnswer(inv -> inv.getArgument(0, String.class).startsWith("nomail")
                ? MailDomain.NO_MAIL_SERVER : MailDomain.ACCEPTS_MAIL);
    }

    @Test
    void rowsSharingAContactPointBecomeOneLeadAndABridgingRowMergesTwoLeads() {
        ImportReport report = upload("""
                name,company,email,phone
                Asha,Hearth Foods,asha@hearth.example,
                Asha R,,"asha@hearth.example;a.rao@hearth.example",
                Ravi,Spice Co,ravi@spice.example,+91 98765 43210
                ,,,+919876543210
                ,,"a.rao@hearth.example;ravi@spice.example",
                Nobody,No Contact,,
                Typo,,asha@@broken,
                """);

        assertThat(report.rowsTotal()).isEqualTo(7);
        assertThat(report.rowsRejected()).isEqualTo(1);       // no email or phone
        assertThat(report.leadsCreated()).isEqualTo(3);       // Asha, Ravi, Typo
        assertThat(report.audienceSize()).isEqualTo(2);       // row 6 merged Asha and Ravi into one lead
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_creator_lead WHERE tenant_id = ?", Integer.class, riya)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_creator_lead_source WHERE tenant_id = ?", Integer.class, riya))
                .isEqualTo(7);

        LeadView merged = lead("Asha");
        assertThat(merged.company()).isEqualTo("Hearth Foods");
        assertThat(merged.emails()).extracting(e -> e.value())
                .containsExactlyInAnyOrder("asha@hearth.example", "a.rao@hearth.example", "ravi@spice.example");
        assertThat(merged.phones()).extracting(p -> p.value()).containsExactly("+919876543210");
        LeadView typo = lead("Typo");
        assertThat(typo.emails()).isEmpty();
        assertThat(typo.discarded()).isEqualTo(1);
        assertThat(typo.reachable()).isFalse();
    }

    @Test
    void onceSomethingIsValidatedOnlyValidatedContactPointsShow() {
        upload("""
                name,email
                Meera,"meera@nomail.example;meera@hearth.example;m@other.example"
                """);
        assertThat(lead("Meera").emails()).hasSize(3);                       // nothing validated yet: all shown

        validator.validatePending();

        LeadView meera = lead("Meera");
        assertThat(meera.emails()).extracting(e -> e.status()).containsOnly("LIKELY_VALID");
        assertThat(meera.emails()).extracting(e -> e.value()).containsExactlyInAnyOrder("meera@hearth.example", "m@other.example");
        assertThat(meera.discarded()).isEqualTo(1);                          // nomail.example has no mail server
    }

    @Test
    void anAudienceSendQueuesOneMailPerLeadAndTheDispatcherSendsThem() {
        upload("""
                name,email
                Asha,"asha@hearth.example;asha.alt@hearth.example"
                Ravi,ravi@spice.example
                Gone,gone@spice.example
                Dead,dead@nomail.example
                """);
        validator.validatePending();
        store.suppress(hasher.hash("gone@spice.example"), "UNSUBSCRIBED");

        AudienceSendResult result = outreach.sendToAudience(riya, new AudienceSendRequest(list, OutreachTemplate.SHOWCASE_WORK, null,
                "pubriya0001", "Hello from Riya"));

        assertThat(result.queued()).isEqualTo(2);
        assertThat(result.suppressed()).isEqualTo(1);
        assertThat(result.noReachableContact()).isEqualTo(1);               // Dead: only an INVALID address
        verify(creatorSender, times(0)).send(any());

        OutreachDispatchJob.RunSummary run = dispatch.run();
        assertThat(run.sent()).isEqualTo(2);
        ArgumentCaptor<CreatorEmailMessage> mails = ArgumentCaptor.forClass(CreatorEmailMessage.class);
        verify(creatorSender, times(2)).send(mails.capture());
        assertThat(mails.getAllValues()).extracting(m -> m.to().get(0)).hasSize(2)
                .containsAnyOf("asha@hearth.example", "asha.alt@hearth.example").contains("ravi@spice.example");
        assertThat(dispatch.run().sent()).isZero();

        OutreachAnalyticsService.AnalyticsView view = analytics.view(riya, 30);
        assertThat(view.audiences()).singleElement().satisfies(a -> {
            assertThat(a.leads()).isEqualTo(4);
            assertThat(a.reachable()).isEqualTo(2);
            assertThat(a.sent()).isEqualTo(2);
        });
        assertThat(view.templates()).filteredOn(t -> t.name().equals("Share a film")).singleElement()
                .satisfies(t -> assertThat(t.sent()).isEqualTo(2));
    }

    @Test
    void creatorTemplatesFillPlaceholdersAndGlobalsStayReadOnly() {
        EmailTemplateService.TemplateView mine = templates.create(riya, new EmailTemplateService.TemplateRequest("Festive",
                OutreachTemplate.SIMILAR_BRAND_WORK, "{name}, a {industry} film for Diwali", "Made {film} for a {industry} brand.", null));

        var preview = outreach.preview(riya, new PreviewRequest(null, mine.id(), "pubriya0001", null, "Asha"));
        assertThat(preview.subject()).isEqualTo("Asha, a food beverage film for Diwali");
        assertThat(preview.text()).contains("Made Video riya0000001 for a food beverage brand.");

        assertThat(templates.usableBy(riya)).extracting(EmailTemplateService.TemplateView::name)
                .containsExactly("Share a film", "Made for a brand like yours", "My recent work", "Festive");
        UUID global = templates.usableBy(riya).get(0).id();
        assertThatThrownBy(() -> templates.deactivate(riya, global)).isInstanceOf(IllegalArgumentException.class);

        var sent = outreach.send(riya, new SendRequest(null, mine.id(), "pubriya0001", null,
                List.of(new Recipient("buyer@hearth.example", "Asha", null))));
        assertThat(sent.results().get(0).outcome()).isEqualTo(Outcome.SENT);
        ArgumentCaptor<CreatorEmailMessage> mail = ArgumentCaptor.forClass(CreatorEmailMessage.class);
        verify(creatorSender).send(mail.capture());
        assertThat(mail.getValue().subject()).isEqualTo("Asha, a food beverage film for Diwali");
    }

    @Test
    void anAddressWithNoMailServerIsRefusedAtSendTimeAndTheMailIsGivenBack() {
        var sent = outreach.send(riya, new SendRequest(OutreachTemplate.SHOWCASE_WORK, null, "pubriya0001", null,
                List.of(new Recipient("x@nomail.example", null, null))));
        assertThat(sent.results().get(0).outcome()).isEqualTo(Outcome.UNDELIVERABLE);
        assertThat(sent.allowance().freeUsedThisWeek()).isZero();
        verify(creatorSender, times(0)).send(any());
    }

    @Test
    void aClickOnATrackedLinkVerifiesTheAddress() {
        upload("name,email\nAsha,asha@hearth.example\n");
        outreach.send(riya, new SendRequest(OutreachTemplate.SHOWCASE_WORK, null, "pubriya0001", null,
                List.of(new Recipient("asha@hearth.example", null, null))));
        String token = jdbc.queryForObject("SELECT token FROM lead_outreach_link", String.class);

        store.recipientOfLink(token).ifPresent(hash -> validator.markVerifiedByHash(hash, "CLICKED"));

        assertThat(lead("Asha").emails()).singleElement().satisfies(e -> assertThat(e.status()).isEqualTo("VERIFIED"));
    }

    @Test
    void anotherCreatorCannotReachThisAudience() {
        UUID other = tenant(jdbc, "Arjun");
        assertThatThrownBy(() -> audiences.leads(other, list, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imports.importCsv(other, list, "x.csv", "email\na@b.example\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ImportReport upload(String csv) {
        return imports.importCsv(riya, list, "leads.csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private LeadView lead(String name) {
        return audiences.leads(riya, list, null, 0).leads().stream().filter(l -> name.equals(l.name())).findFirst().orElseThrow();
    }
}
