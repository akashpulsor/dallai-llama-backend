package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.leadmanagement.brand.BrandAccountService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignInRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.brand.BrandSessionService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.leadmanagement.inquiry.CreativePlanningBriefClient;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryConvertedRequestCounter;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BrandInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.CreatorInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.inquiry.ProjectReviewLinks;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.routing.CreatorRequestNotifier;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Request a video" from the landing page, routed to a creator by each strategy, on a real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "brands.session-secret=test-secret"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestRouter.class, RequestRoutingStore.class, CreatorRequestNotifier.class,
        BrandAccountService.class, BrandSessionService.class, BrandInquiryService.class, InquiryConvertedRequestCounter.class,
        com.dalai.llama.tenant.leadmanagement.inquiry.InquiryImageStore.class, CreatorProfileService.class, HandlePolicy.class,
        com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator.class,
        com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher.class, RequestRoutingDbTest.TestConfig.class})
class RequestRoutingDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({BrandProperties.class, ShowcaseProperties.class,
            com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private PlatformMailer mailer;
    @MockBean private CreativePlanningBriefClient briefClient;
    @MockBean private ProjectReviewLinks reviewLinks;
    @MockBean private com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver domains;
    @Autowired private BrandAccountService accounts;
    @Autowired private BrandSessionService sessions;
    @Autowired private BrandInquiryService inquiries;
    @Autowired private RequestRouter router;
    @Autowired private RequestRoutingStore store;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID akash;
    private UUID riya;
    private UUID tech;
    private UUID brand;
    private int tick;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        when(mailer.send(any())).thenReturn(true);
        when(reviewLinks.of(any(), any())).thenReturn(new ProjectReviewLinks.Progress(false, null));
        akash = creator("Akash", ShowcaseIndustry.FOOD_BEVERAGE);
        riya = creator("Riya Motion", ShowcaseIndustry.FOOD_BEVERAGE);
        tech = creator("Byte Films", ShowcaseIndustry.TECH);
        brand = signedInBrand("ask@hearth.example");
    }

    @Test
    void roundRobinGoesToTheIndustrysCreatorsInTurn_andADeclineMovesItOnUntilOpsHaveIt() {
        UUID first = request(ShowcaseIndustry.FOOD_BEVERAGE);
        UUID second = request(ShowcaseIndustry.FOOD_BEVERAGE);
        assertThat(holder(first)).isEqualTo(akash);
        assertThat(holder(second)).isEqualTo(riya);

        // Passed on: the next creator for the industry, never back to the one who passed.
        inquiries.setStatus(riya, second, BrandInquiry.Status.DECLINED);
        assertThat(holder(second)).isEqualTo(akash);
        inquiries.setStatus(akash, second, BrandInquiry.Status.DECLINED);
        assertThat(holder(second)).isEqualTo(tech);   // nobody left for the industry: any active creator
        inquiries.setStatus(tech, second, BrandInquiry.Status.DECLINED);
        assertThat(holder(second)).isNull();
        assertThat(store.unassigned()).containsExactly(second);

        // The brand sees it still looking for a creator.
        BrandInquiryView seen = inquiries.forBrand(brand).stream().filter(v -> v.id().equals(second)).findFirst().orElseThrow();
        assertThat(seen.creatorHandle()).isNull();
        assertThat(seen.status()).isEqualTo(BrandInquiry.Status.NEW);
    }

    @Test
    void offerToAllShowsItToEveryActiveCreator_andTheFirstToAcceptTakesIt() {
        store.setStrategy(RoutingStrategy.OFFER_TO_ALL);
        UUID request = request(ShowcaseIndustry.FOOD_BEVERAGE);
        assertThat(holder(request)).isNull();
        for (UUID creator : List.of(akash, riya, tech)) {
            assertThat(inquiries.forCreator(creator)).singleElement().extracting(CreatorInquiryView::offered).isEqualTo(true);
        }

        CreatorInquiryView taken = inquiries.accept(riya, request);
        assertThat(taken.offered()).isFalse();
        assertThat(holder(request)).isEqualTo(riya);
        assertThat(inquiries.forCreator(akash)).isEmpty();
        assertThatThrownBy(() -> inquiries.accept(akash, request)).hasMessageContaining("isn't offered to you");
    }

    @Test
    void opsAssignsLeavesItWaitingUntilOpsPickTheCreator() {
        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        UUID request = request(null);
        assertThat(holder(request)).isNull();
        assertThat(store.unassigned()).containsExactly(request);

        router.assignByOps(request, tech);
        assertThat(holder(request)).isEqualTo(tech);
        assertThat(store.unassigned()).isEmpty();
        assertThatThrownBy(() -> router.assignByOps(request, akash)).hasMessageContaining("already has a creator");
    }

    @Test
    void onceTheBriefIsPaidAndTheReviewShared_theBrandPageOpensTheSameReviewLink() {
        UUID request = request(ShowcaseIndustry.FOOD_BEVERAGE);
        UUID requirement = UUID.randomUUID();
        when(briefClient.createBrief(eq(akash), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new CreativePlanningBriefClient.CreatedBrief(requirement, "brief-token"));
        inquiries.convert(akash, request);
        when(reviewLinks.of(akash, requirement))
                .thenReturn(new ProjectReviewLinks.Progress(true, "https://creator.dalaillama.in/review/abc123"));

        BrandInquiryView seen = inquiries.forBrand(brand).get(0);
        assertThat(seen.creatorHandle()).isEqualTo("akash");
        assertThat(seen.funded()).isTrue();
        assertThat(seen.reviewUrl()).isEqualTo("https://creator.dalaillama.in/review/abc123");
    }

    private UUID request(ShowcaseIndustry industry) {
        clock.set(NOW.plusSeconds(++tick * 60L));
        return inquiries.submitToDalaillama(brand, new SubmitInquiryRequest(null, null, BrandInquiry.BudgetBand.NOT_SURE,
                BrandInquiry.Timeline.THIS_MONTH, "A 30s film for our ghee", null, null, null, null, 30, List.of("English"), industry),
                List.of(), List.of()).id();
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
        verify(mailer, atLeastOnce()).send(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        return sessions.resolve(accounts.completeSignIn(token).session().token()).orElseThrow();
    }
}
