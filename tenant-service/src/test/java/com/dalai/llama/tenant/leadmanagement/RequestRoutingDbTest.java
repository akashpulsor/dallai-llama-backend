package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.leadmanagement.brand.BrandAccountService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.GuestContact;
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
        com.dalai.llama.tenant.leadmanagement.inquiry.RequestPricing.class,
        com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator.class,
        com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher.class, RequestRoutingDbTest.TestConfig.class})
class RequestRoutingDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({BrandProperties.class, ShowcaseProperties.class,
            com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties.class,
            com.dalai.llama.tenant.leadmanagement.inquiry.RequestPricingProperties.class})
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
        // creative-planning is down unless a test says otherwise, so requests stay with the router.
        when(briefClient.createBrief(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException("down", null));
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
    void everyActiveCreatorTakesRoutedRequestsUntilOpsSwitchThemOff() {
        // A creator nobody ever configured is on.
        jdbc.update("DELETE FROM request_routing_creator WHERE tenant_id = ?", riya);
        store.setAccepting(akash, false);
        store.setAccepting(tech, false);

        assertThat(holder(request(ShowcaseIndustry.FOOD_BEVERAGE))).isEqualTo(riya);
        assertThat(store.creators()).extracting(RequestRoutingStore.RoutedCreator::handle, RequestRoutingStore.RoutedCreator::accepting)
                .contains(org.assertj.core.groups.Tuple.tuple("akash", false), org.assertj.core.groups.Tuple.tuple("riya-motion", true));

        store.setAccepting(riya, false);
        UUID waiting = request(ShowcaseIndustry.FOOD_BEVERAGE);
        assertThat(holder(waiting)).isNull();
        assertThat(store.waiting()).singleElement().satisfies(w -> {
            assertThat(w.id()).isEqualTo(waiting);
            assertThat(w.brand()).isEqualTo("Hearth Foods");
            assertThat(w.message()).isEqualTo("A 30s film for our ghee");
        });
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
        org.mockito.Mockito.reset(briefClient);
        when(briefClient.createBrief(eq(akash), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new CreativePlanningBriefClient.CreatedBrief(requirement, "brief-token", null, null, null));
        inquiries.convert(akash, request);
        when(reviewLinks.of(akash, requirement))
                .thenReturn(new ProjectReviewLinks.Progress(true, "https://creator.dalaillama.in/review/abc123"));

        BrandInquiryView seen = inquiries.forBrand(brand).get(0);
        assertThat(seen.creatorHandle()).isEqualTo("akash");
        assertThat(seen.funded()).isTrue();
        assertThat(seen.reviewUrl()).isEqualTo("https://creator.dalaillama.in/review/abc123");
    }

    @Test
    void aGuestRequestMakesAnUnverifiedBrand_isRoutedLikeAnyOther_andMailsAReceipt() {
        UUID id = inquiries.submitAsGuest(new GuestContact(" Ravi@Ghar.example ", "Ravi", "Ghar Pickles", ""), guestRequest(),
                List.of(), List.of()).id();

        assertThat(holder(id)).isEqualTo(akash);
        assertThat(jdbc.queryForMap("SELECT c.source, c.verified_at, c.contact_name, c.company_name, c.website_url, c.industry "
                + "FROM lead_brand_contact c JOIN lead_brand_inquiry i ON i.brand_contact_id = c.id WHERE i.id = ?", id))
                .containsEntry("source", "REQUEST").containsEntry("verified_at", null).containsEntry("contact_name", "Ravi")
                .containsEntry("company_name", "Ghar Pickles").containsEntry("website_url", null)
                .containsEntry("industry", "FOOD_BEVERAGE");
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).sendAfterCommit(mail.capture());
        assertThat(mail.getAllValues()).anySatisfy(m -> {
            assertThat(m.to()).isEqualTo("ravi@ghar.example");
            assertThat(m.subject()).isEqualTo("We've got your video request");
        });
    }

    @Test
    void aGuestUsingAKnownBrandsEmail_addsTheRequestToThatBrand_withoutChangingItsDetails() {
        UUID id = inquiries.submitAsGuest(new GuestContact("ask@hearth.example", "Someone Else", "Other Co", "https://other.example"),
                guestRequest(), List.of(), List.of()).id();

        assertThat(inquiries.forBrand(brand)).extracting(BrandInquiryView::id).contains(id);
        assertThat(jdbc.queryForMap("SELECT source, contact_name, company_name, website_url FROM lead_brand_contact WHERE id = ?", brand))
                .containsEntry("source", "SIGNUP").containsEntry("contact_name", "Asha")
                .containsEntry("company_name", "Hearth Foods").containsEntry("website_url", "https://hearth.example");
    }

    @Test
    void aRequestToDalaillama_isABriefAtThePlatformPrice_readyToPay_theMomentItIsRouted() {
        briefsAreUp();
        var submitted = inquiries.submitAsGuest(new GuestContact("ravi@ghar.example", "Ravi", "Ghar Pickles", null), guestRequest(),
                List.of(), List.of());

        assertThat(submitted.status()).isEqualTo(BrandInquiry.Status.CONVERTED);
        assertThat(submitted.briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/brief-" + akash);
        // The platform's rate (Rs 4,999 a minute), 30% upfront: a 30s video is Rs 2,500.
        verify(briefClient).createBrief(eq(akash), anyString(), eq("Ghar Pickles"), eq("food beverage"), any(), any(),
                eq(new CreativePlanningBriefClient.Pricing(new java.math.BigDecimal("83.3167"), 30)));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).sendAfterCommit(mail.capture());
        assertThat(mail.getAllValues()).filteredOn(m -> m.to().equals("ravi@ghar.example")).singleElement()
                .satisfies(m -> {
                    assertThat(m.subject()).isEqualTo("Your video brief is ready");
                    assertThat(m.text()).contains("costs ₹2,500; pay ₹750 now");
                });
    }

    @Test
    void opsAssigningTwice_isDoneOnce_notAnError() {
        briefsAreUp();
        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        UUID waiting = request(null);
        inquiries.assignByOps(waiting, tech);
        inquiries.assignByOps(waiting, tech);   // a double click while the brief was being made

        assertThat(holder(waiting)).isEqualTo(tech);
        verify(briefClient, org.mockito.Mockito.times(1)).createBrief(eq(tech), anyString(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> inquiries.assignByOps(waiting, akash)).hasMessageContaining("already has a creator");
    }

    @Test
    void anUnpaidRequestCanBeExpired_byItsCreatorOrOps_andItsBriefLinkStops() {
        briefsAreUp();
        UUID held = request(ShowcaseIndustry.FOOD_BEVERAGE);           // routed to akash, brief made
        UUID requirement = jdbc.queryForObject("SELECT requirement_id FROM lead_brand_inquiry WHERE id = ?", UUID.class, held);
        assertThat(store.unpaid()).extracting(RequestRoutingStore.UnpaidRequest::id).contains(held);

        assertThat(inquiries.expireByCreator(akash, held).status()).isEqualTo(BrandInquiry.Status.EXPIRED);
        verify(briefClient).withdrawLink(akash, requirement);
        assertThat(store.unpaid()).extracting(RequestRoutingStore.UnpaidRequest::id).doesNotContain(held);
        assertThat(inquiries.forBrand(brand).stream().filter(v -> v.id().equals(held)).findFirst().orElseThrow().briefUrl()).isNull();

        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        UUID waiting = request(null);
        inquiries.expireByOps(waiting);
        assertThat(store.waiting()).isEmpty();

        store.setStrategy(RoutingStrategy.ROUND_ROBIN_BY_INDUSTRY);
        UUID paid = request(ShowcaseIndustry.FOOD_BEVERAGE);
        inquiries.onBriefPaid(jdbc.queryForObject("SELECT requirement_id FROM lead_brand_inquiry WHERE id = ?", UUID.class, paid),
                new java.math.BigDecimal("750"), "INR");
        assertThatThrownBy(() -> inquiries.expireByOps(paid)).hasMessageContaining("already paid");
    }

    @Test
    void thePlatformPriceIsPerSecond_fromItsSixtySecondPrice() {
        briefsAreUp();
        inquiries.submitToDalaillama(brand, new SubmitInquiryRequest(null, null, BrandInquiry.BudgetBand.NOT_SURE,
                BrandInquiry.Timeline.THIS_MONTH, "A 90s brand film", null, null, null, null, 90, List.of("English"),
                ShowcaseIndustry.FOOD_BEVERAGE, null), List.of(), List.of());
        verify(briefClient).createBrief(any(), anyString(), any(), any(), any(), any(),
                eq(new CreativePlanningBriefClient.Pricing(new java.math.BigDecimal("83.3167"), 30)));
    }

    @Test
    void aRequestNobodyHeldYet_becomesABriefWhenACreatorAcceptsOrOpsAssign() {
        briefsAreUp();
        store.setStrategy(RoutingStrategy.OFFER_TO_ALL);
        UUID offered = request(ShowcaseIndustry.FOOD_BEVERAGE);
        assertThat(inquiries.forCreator(akash).get(0).briefUrl()).isNull();
        assertThat(inquiries.accept(riya, offered).briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/brief-" + riya);

        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        UUID waiting = request(null);
        inquiries.assignByOps(waiting, tech);
        assertThat(inquiries.forCreator(tech).get(0).briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/brief-" + tech);
    }

    @Test
    void aRequestFromACreatorsMail_goesToThatCreator_whateverTheStrategy_whileTheyTakeRequests() {
        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        // Byte Films mailed a food brand: their request goes to them, not to the food creators or ops.
        assertThat(holder(requestFrom("byte-films", ShowcaseIndustry.FOOD_BEVERAGE))).isEqualTo(tech);

        store.setStrategy(RoutingStrategy.OFFER_TO_ALL);
        assertThat(holder(requestFrom("riya-motion", ShowcaseIndustry.FOOD_BEVERAGE))).isEqualTo(riya);

        // Not taking requests (or an unknown handle): routed as usual.
        store.setAccepting(riya, false);
        store.setStrategy(RoutingStrategy.OPS_ASSIGNS);
        assertThat(holder(requestFrom("riya-motion", ShowcaseIndustry.FOOD_BEVERAGE))).isNull();
        assertThat(holder(requestFrom("nobody-here", ShowcaseIndustry.FOOD_BEVERAGE))).isNull();
    }

    private UUID requestFrom(String creatorHandle, ShowcaseIndustry industry) {
        clock.set(NOW.plusSeconds(++tick * 60L));
        return inquiries.submitToDalaillama(brand, new SubmitInquiryRequest(null, null, BrandInquiry.BudgetBand.NOT_SURE,
                BrandInquiry.Timeline.THIS_MONTH, "A 30s film like the one you mailed", null, null, null, null, 30, List.of("English"),
                industry, creatorHandle), List.of(), List.of()).id();
    }

    /** creative-planning answers, with one brief per creator. */
    private void briefsAreUp() {
        org.mockito.Mockito.reset(briefClient);
        when(briefClient.createBrief(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            UUID creator = call.getArgument(0);
            CreativePlanningBriefClient.Pricing price = call.getArgument(6);
            java.math.BigDecimal total = price.pricePerSecond() == null ? new java.math.BigDecimal("3000")
                    : price.pricePerSecond().multiply(java.math.BigDecimal.valueOf(30)).setScale(0, java.math.RoundingMode.HALF_UP);
            return new CreativePlanningBriefClient.CreatedBrief(UUID.randomUUID(), "brief-" + creator, total,
                    total.multiply(java.math.BigDecimal.valueOf(price.requiredPaymentPercent())).divide(java.math.BigDecimal.valueOf(100)), "INR");
        });
    }

    private static SubmitInquiryRequest guestRequest() {
        return new SubmitInquiryRequest(null, null, BrandInquiry.BudgetBand.NOT_SURE, BrandInquiry.Timeline.THIS_MONTH,
                "A 30s reel for our mango pickle", null, null, null, null, 30, List.of("Hindi"), ShowcaseIndustry.FOOD_BEVERAGE, null);
    }

    private UUID request(ShowcaseIndustry industry) {
        clock.set(NOW.plusSeconds(++tick * 60L));
        return inquiries.submitToDalaillama(brand, new SubmitInquiryRequest(null, null, BrandInquiry.BudgetBand.NOT_SURE,
                BrandInquiry.Timeline.THIS_MONTH, "A 30s film for our ghee", null, null, null, null, 30, List.of("English"), industry, null),
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
        verify(mailer, atLeastOnce()).sendAfterCommit(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        return sessions.resolve(accounts.completeSignIn(token).session().token()).orElseThrow();
    }
}
