package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.leadmanagement.brand.BrandAccountService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignInRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.brand.BrandSessionService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BriefHandover;
import com.dalai.llama.tenant.leadmanagement.inquiry.CreativePlanningBriefClient;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryConvertedRequestCounter;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BrandInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.inquiry.MatchingTimeoutJob;
import com.dalai.llama.tenant.leadmanagement.inquiry.ProjectReviewLinks;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.routing.CreatorRequestNotifier;
import com.dalai.llama.tenant.leadmanagement.routing.RequestMatchingProperties;
import com.dalai.llama.tenant.leadmanagement.routing.RequestRouter;
import com.dalai.llama.tenant.leadmanagement.routing.RequestRoutingStore;
import com.dalai.llama.tenant.leadmanagement.routing.RoutingStrategy;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A request sent to Dalaillama with the founder's account set: the brand pays first, creators are
 * offered it after (like a ride waiting for a driver), and nobody taking it means the founder makes
 * it. The brand never sees a request without a creator. Real Postgres.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "brands.session-secret=test-secret", "brands.request-matching.house-creator-email=Founder@Dalaillama.example"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestRouter.class, RequestRoutingStore.class, CreatorRequestNotifier.class, MatchingTimeoutJob.class,
        BrandAccountService.class, BrandSessionService.class, BrandInquiryService.class, InquiryConvertedRequestCounter.class,
        com.dalai.llama.tenant.leadmanagement.inquiry.InquiryImageStore.class, CreatorProfileService.class, HandlePolicy.class,
        com.dalai.llama.tenant.leadmanagement.inquiry.RequestPricing.class,
        com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator.class,
        com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher.class, RequestMatchingDbTest.TestConfig.class})
class RequestMatchingDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({BrandProperties.class, ShowcaseProperties.class,
            com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties.class,
            com.dalai.llama.tenant.leadmanagement.inquiry.RequestPricingProperties.class, RequestMatchingProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private PlatformMailer mailer;
    @MockBean private CreativePlanningBriefClient briefClient;
    @MockBean private ProjectReviewLinks reviewLinks;
    @MockBean private BriefHandover handover;
    @MockBean private com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver domains;
    @Autowired private BrandAccountService accounts;
    @Autowired private BrandSessionService sessions;
    @Autowired private BrandInquiryService inquiries;
    @Autowired private RequestRoutingStore store;
    @Autowired private MatchingTimeoutJob timeouts;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID founder;
    private UUID riya;
    private UUID tech;
    private UUID brand;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        when(mailer.send(any())).thenReturn(true);
        when(reviewLinks.of(any(), any())).thenReturn(new ProjectReviewLinks.Progress(false, null));
        when(briefClient.createBrief(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(call ->
                new CreativePlanningBriefClient.CreatedBrief(UUID.randomUUID(), "brief-" + UUID.randomUUID(), new BigDecimal("1000"),
                        new BigDecimal("300"), "INR"));
        founder = creator("Akash Tripathi", ShowcaseIndustry.FOOD_BEVERAGE);
        jdbc.update("UPDATE tenants SET admin_user_email = 'founder@dalaillama.example' WHERE id = ?", founder);
        riya = creator("Riya Motion", ShowcaseIndustry.FOOD_BEVERAGE);
        tech = creator("Byte Films", ShowcaseIndustry.TECH);
        brand = signedInBrand("ask@hearth.example");
        org.mockito.Mockito.clearInvocations(mailer);
    }

    @Test
    void theBrandPaysAtOnce_creatorsAreOfferedItAfter_andTheFirstToAcceptGetsTheBriefAndPayment() {
        store.setStrategy(RoutingStrategy.OFFER_TO_ALL);
        var submitted = request();
        UUID id = submitted.id();

        // Held by the founder's account: a brief to pay right away, no creator named, none shown.
        assertThat(submitted.briefUrl()).isNotNull();
        assertThat(holder(id)).isEqualTo(founder);
        verify(briefClient).createBrief(eq(founder), anyString(), any(), any(), any(), any(), any());
        assertThat(brandMail("Your video brief is ready").text()).contains("your creator is assigned the moment you pay")
                .doesNotContain("Akash");
        assertThat(view(id).creatorHandle()).isNull();
        assertThat(inquiries.forCreator(riya)).isEmpty();   // nothing offered before it's paid

        pay(id);
        assertThat(brandMail("Payment received: assigning your creator").text()).contains("within 2 hours");
        assertThat(view(id).matchingUntil()).isEqualTo(NOW.plusSeconds(7200).atOffset(java.time.ZoneOffset.UTC));
        assertThat(inquiries.forCreator(riya)).singleElement().satisfies(v -> {
            assertThat(v.offered()).isTrue();
            assertThat(v.paid()).isTrue();
        });
        assertThat(inquiries.forCreator(tech)).hasSize(1);
        assertThat(store.offeredTo(founder)).isEmpty();      // the founder only keeps what nobody takes

        inquiries.accept(riya, id);
        assertThat(holder(id)).isEqualTo(riya);
        verify(handover).handOver(founder, requirement(id), riya);
        assertThat(view(id).creatorHandle()).isEqualTo("riya-motion");
        assertThat(view(id).matchingUntil()).isNull();
        assertThat(brandMail("Your creator: Riya Motion")).isNotNull();
        assertThatThrownBy(() -> inquiries.accept(tech, id)).hasMessageContaining("isn't offered to you");
    }

    @Test
    void nobodyTakingItInTime_leavesItWithTheFounder_andTheBrandHearsOnlyWhoMakesIt() {
        store.setStrategy(RoutingStrategy.OFFER_TO_ALL);
        UUID id = request().id();
        pay(id);

        clock.set(NOW.plusSeconds(7199));
        timeouts.run();
        assertThat(view(id).matchingUntil()).isNotNull();

        clock.set(NOW.plusSeconds(7201));
        timeouts.run();
        assertThat(holder(id)).isEqualTo(founder);
        assertThat(view(id).creatorName()).isEqualTo("Akash Tripathi");
        assertThat(brandMail("Your creator: Akash Tripathi")).isNotNull();
        assertThat(inquiries.forCreator(riya)).isEmpty();
        assertThatThrownBy(() -> inquiries.accept(riya, id)).hasMessageContaining("isn't offered to you");
        verify(handover, never()).handOver(any(), any(), any());
    }

    @Test
    void roundRobinOffersItOneCreatorAtATime_andWhenEveryonePasses_theFounderHasItAtOnce() {
        UUID id = request().id();
        pay(id);
        assertThat(store.offeredTo(riya)).containsExactly(id);
        assertThat(store.offeredTo(tech)).isEmpty();

        inquiries.setStatus(riya, id, BrandInquiry.Status.DECLINED);
        assertThat(store.offeredTo(tech)).containsExactly(id);
        assertThat(holder(id)).isEqualTo(founder);
        assertThat(view(id).matchingUntil()).isNotNull();

        inquiries.setStatus(tech, id, BrandInquiry.Status.DECLINED);
        assertThat(view(id).matchingUntil()).isNull();
        assertThat(view(id).creatorName()).isEqualTo("Akash Tripathi");
    }

    @Test
    void withNoOtherCreatorTakingRequests_thePaidRequestIsTheFoundersStraightAway() {
        store.setAccepting(riya, false);
        store.setAccepting(tech, false);
        UUID id = request().id();
        pay(id);

        assertThat(view(id).creatorName()).isEqualTo("Akash Tripathi");
        assertThat(view(id).matchingUntil()).isNull();
        assertThat(brandMail("Your creator: Akash Tripathi")).isNotNull();
    }

    @Test
    void opsCanHandAHeldRequestToACreator_paidOrNot() {
        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        UUID paid = request().id();
        pay(paid);
        assertThat(store.waiting()).extracting(RequestRoutingStore.WaitingRequest::id).contains(paid);

        inquiries.assignByOps(paid, tech);
        assertThat(holder(paid)).isEqualTo(tech);
        verify(handover).handOver(founder, requirement(paid), tech);
        assertThat(brandMail("Your creator: Byte Films")).isNotNull();
        assertThat(store.waiting()).extracting(RequestRoutingStore.WaitingRequest::id).doesNotContain(paid);

        UUID unpaid = request().id();
        inquiries.assignByOps(unpaid, riya);
        verify(handover).handOver(founder, requirement(unpaid), riya);
        assertThat(view(unpaid).creatorHandle()).isEqualTo("riya-motion");
    }

    private com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmittedInquiry request() {
        clock.set(clock.instant().plusSeconds(60));
        return inquiries.submitToDalaillama(brand, new SubmitInquiryRequest(null, null, BrandInquiry.BudgetBand.NOT_SURE,
                BrandInquiry.Timeline.THIS_MONTH, "A 30s film for our ghee", null, null, null, null, 30, List.of("English"),
                ShowcaseIndustry.FOOD_BEVERAGE, null), List.of(), List.of());
    }

    private void pay(UUID inquiryId) {
        clock.set(NOW);
        inquiries.onBriefPaid(requirement(inquiryId), new BigDecimal("300"), "INR");
    }

    private UUID requirement(UUID inquiryId) {
        return jdbc.queryForObject("SELECT requirement_id FROM lead_brand_inquiry WHERE id = ?", UUID.class, inquiryId);
    }

    private BrandInquiryView view(UUID inquiryId) {
        return inquiries.forBrand(brand).stream().filter(v -> v.id().equals(inquiryId)).findFirst().orElseThrow();
    }

    private PlatformMailer.Mail brandMail(String subject) {
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).sendAfterCommit(mail.capture());
        return mail.getAllValues().stream().filter(m -> m.to().equals("ask@hearth.example") && m.subject().equals(subject))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("No mail to the brand: " + subject));
    }

    private UUID holder(UUID inquiryId) {
        return jdbc.queryForObject("SELECT tenant_id FROM lead_brand_inquiry WHERE id = ?", UUID.class, inquiryId);
    }

    private UUID creator(String name, ShowcaseIndustry industry) {
        UUID id = tenant(jdbc, name);
        profileService.ensureProfile(id, name);
        jdbc.update("INSERT INTO creator_profile_industry (tenant_id, industry) VALUES (?, ?)", id, industry.name());
        store.setAccepting(id, true);
        return id;
    }

    private UUID signedInBrand(String email) {
        accounts.requestSignIn(new SignInRequest(email, "Asha", "Hearth Foods", "https://hearth.example",
                ShowcaseIndustry.FOOD_BEVERAGE, "in", false, null));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).sendAfterCommit(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        return sessions.resolve(accounts.completeSignIn(token).session().token()).orElseThrow();
    }
}
