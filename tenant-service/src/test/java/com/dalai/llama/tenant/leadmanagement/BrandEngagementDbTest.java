package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.brand.BrandAccountService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignInRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.brand.BrandSessionService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.leadmanagement.inquiry.CreativePlanningBriefClient;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryConvertedRequestCounter;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.CreatorInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.repository.ShowcasePlayStore;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.FollowService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import com.dalai.llama.tenant.showcase.service.ShowcaseEngagementService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase B on a real Postgres: brand sign-in by email link, anonymous likes, follows, and a request
 * becoming a brief (which the ranking then counts). Mail and creative-planning are the only fakes. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "brands.session-secret=test-secret"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({com.dalai.llama.tenant.leadmanagement.routing.RequestRouter.class, com.dalai.llama.tenant.leadmanagement.routing.RequestRoutingStore.class,
        com.dalai.llama.tenant.leadmanagement.routing.CreatorRequestNotifier.class,
        BrandAccountService.class, BrandSessionService.class, BrandInquiryService.class, InquiryConvertedRequestCounter.class,
        com.dalai.llama.tenant.leadmanagement.inquiry.InquiryImageStore.class,
        com.dalai.llama.tenant.leadmanagement.inquiry.RequestPricing.class,
        FollowService.class, ShowcaseEngagementService.class, ShowcasePlayStore.class, CreatorProfileService.class,
        HandlePolicy.class, com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator.class,
        com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher.class, BrandEngagementDbTest.TestConfig.class})
class BrandEngagementDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

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
    @MockBean private com.dalai.llama.tenant.leadmanagement.inquiry.ProjectReviewLinks reviewLinks;
    @MockBean private com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver domains;
    @Autowired private BrandAccountService accounts;
    @Autowired private BrandSessionService sessions;
    @Autowired private BrandInquiryService inquiries;
    @Autowired private InquiryConvertedRequestCounter convertedCounter;
    @Autowired private FollowService follows;
    @Autowired private ShowcaseEngagementService engagement;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID creator;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        creator = tenant(jdbc, "Riya Motion");
        profileService.ensureProfile(creator, "Riya Motion");
        verifiedChannel(jdbc, creator, "UCriya", NOW);
        video(jdbc, "film0000001", "UCriya", 1280, 720, NOW);
        jdbc.update("""
                INSERT INTO showcase_item (id, public_id, tenant_id, youtube_video_id, origin, industry, format, status,
                    rights_confirmed_at, published_at)
                VALUES (?, 'pub0000001', ?, 'film0000001', 'EXTERNAL', 'FOOD_BEVERAGE', 'PRODUCT_AD', 'LIVE', ?, ?)""",
                UUID.randomUUID(), creator, Timestamp.from(NOW), Timestamp.from(NOW));
        when(mailer.send(any())).thenReturn(true);
        when(reviewLinks.of(any(), any())).thenReturn(new com.dalai.llama.tenant.leadmanagement.inquiry.ProjectReviewLinks.Progress(false, null));
        // creative-planning is down unless a test says otherwise: requests then wait for the creator to convert them.
        when(briefClient.createBrief(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException("down", null));
    }

    @Test
    void aSignInLinkWorksOnceAndGivesASessionForTheBrand() {
        accounts.requestSignIn(signIn("Buyer@Hearth.example ", "follow:riya-motion"));

        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).sendAfterCommit(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("buyer@hearth.example");
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        // Only the hash is stored.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_brand_sign_in WHERE token_hash = ?", Integer.class,
                PublicTokens.sha256Hex(token))).isEqualTo(1);

        BrandAccountService.SignInResult signedIn = accounts.completeSignIn(token);
        assertThat(signedIn.view().pendingAction()).isEqualTo("follow:riya-motion");
        assertThat(signedIn.view().brand().companyName()).isEqualTo("Hearth Foods");
        assertThat(sessions.resolve(signedIn.session().token())).isPresent();
        assertThatThrownBy(() -> accounts.completeSignIn(token)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredLinkDoesNotSignIn() {
        accounts.requestSignIn(signIn("late@hearth.example", null));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).sendAfterCommit(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");

        clock.set(NOW.plus(Duration.ofMinutes(31)));
        assertThatThrownBy(() -> accounts.completeSignIn(token)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signInLinksAreRateLimitedPerHourWithoutTellingTheCaller() {
        for (int i = 0; i < 7; i++) accounts.requestSignIn(signIn("busy@hearth.example", null));
        verify(mailer, org.mockito.Mockito.times(5)).sendAfterCommit(any());
    }

    @Test
    void likesAreOnePerVisitorAndCanBeTakenBack() {
        UUID visitor = UUID.randomUUID();
        engagement.setLiked("pub0000001", visitor, true);
        engagement.setLiked("pub0000001", visitor, true);
        assertThat(engagement.setLiked("pub0000001", UUID.randomUUID(), true).likeCount()).isEqualTo(2);
        assertThat(engagement.setLiked("pub0000001", visitor, false).likeCount()).isEqualTo(1);
    }

    @Test
    void followingIsIdempotentAndCounted() {
        UUID brand = signedInBrand("follow@hearth.example");
        follows.follow(brand, "riya-motion");
        assertThat(follows.follow(brand, "riya-motion").followerCount()).isEqualTo(1);
        assertThat(accounts.me(brand).following()).extracting(f -> f.handle()).containsExactly("riya-motion");
        assertThat(follows.unfollow(brand, "riya-motion").followerCount()).isZero();
        assertThat(follows.unfollow(brand, "riya-motion").followerCount()).isZero();
    }

    @Test
    void aRequestToACreatorBecomesABriefAtOnce_atTheirRate_withTheirUpfrontShare() {
        UUID brand = signedInBrand("ask@hearth.example");
        UUID requirement = UUID.randomUUID();
        org.mockito.Mockito.reset(briefClient);
        when(briefClient.createBrief(eq(creator), anyString(), eq("Hearth Foods"), eq("food beverage"),
                org.mockito.ArgumentMatchers.argThat(d -> d.durationSeconds() == 30 && "Young parents in metros".equals(d.targetAudience())
                        && "A2 Desi Ghee".equals(d.productName())),
                org.mockito.ArgumentMatchers.argThat(images -> images.size() == 2),
                eq(new CreativePlanningBriefClient.Pricing(null, 40))))
                .thenReturn(new CreativePlanningBriefClient.CreatedBrief(requirement, "share-abc", new java.math.BigDecimal("3000"),
                        new java.math.BigDecimal("1200"), "INR"));
        clearInvocations(mailer);

        var submitted = inquiries.submit(brand, "riya-motion", new SubmitInquiryRequest("pub0000001", null,
                BrandInquiry.BudgetBand.FROM_50K_TO_2L, BrandInquiry.Timeline.THIS_MONTH, "A 30s launch film for our new ghee",
                "A2 Desi Ghee", "Small-batch ghee from grass-fed cows", "Young parents in metros", "Warm, homely, morning kitchen",
                30, java.util.List.of("English", "Hindi"), null),
                java.util.List.of(new org.springframework.mock.web.MockMultipartFile("productImages", "jar.png", "image/png", new byte[]{1, 2, 3})),
                java.util.List.of(new org.springframework.mock.web.MockMultipartFile("referenceImages", "mood.jpg", "image/jpeg", new byte[]{4})));

        // The brand goes straight to the brief, and is mailed it with what to pay now.
        assertThat(submitted.status()).isEqualTo(BrandInquiry.Status.CONVERTED);
        assertThat(submitted.briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/share-abc");
        verify(mailer).sendAfterCommit(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("ask@hearth.example")
                && m.text().contains("https://creator.dalaillama.in/brief/share-abc") && m.text().contains("pay ₹1,200 now")));
        // The creator hears nothing until the brand pays; the film counted the brand's interest.
        verify(mailer, never()).sendAfterCommit(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("owner@example.com")));
        assertThat(jdbc.queryForObject("SELECT inquiry_count FROM showcase_item WHERE public_id = 'pub0000001'", Integer.class))
                .isEqualTo(1);
        CreatorInquiryView seen = inquiries.forCreator(creator).get(0);
        assertThat(seen.status()).isEqualTo(BrandInquiry.Status.CONVERTED);
        assertThat(seen.filmTitle()).isEqualTo("Video film0000001");
        assertThat(seen.brand().companyName()).isEqualTo("Hearth Foods");
        assertThat(seen.brief().productName()).isEqualTo("A2 Desi Ghee");
        assertThat(seen.brief().languages()).containsExactly("English", "Hindi");
        assertThat(seen.brief().images()).extracting(com.dalai.llama.tenant.leadmanagement.inquiry.InquiryImageStore.ImageRef::fileName)
                .containsExactly("jar.png", "mood.jpg");
        var img = inquiries.image(creator, submitted.id(), seen.brief().images().get(0).id()).orElseThrow();
        assertThat(img.contentType()).isEqualTo("image/png");
        assertThat(img.bytes()).containsExactly(1, 2, 3);
        assertThat(inquiries.forBrand(brand).get(0).briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/share-abc");
        assertThat(convertedCounter.convertedRequests(creator)).isEqualTo(1);
        assertThatThrownBy(() -> inquiries.convert(creator, submitted.id())).isInstanceOf(IllegalStateException.class);

        // Paid: the creator is mailed once, however often billing says so (the balance, a redelivery).
        inquiries.onBriefPaid(requirement, new java.math.BigDecimal("1200.00"), "INR");
        inquiries.onBriefPaid(requirement, new java.math.BigDecimal("1800.00"), "INR");
        inquiries.onBriefPaid(UUID.randomUUID(), new java.math.BigDecimal("500"), "INR");
        ArgumentCaptor<PlatformMailer.Mail> toCreator = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, org.mockito.Mockito.atLeastOnce()).sendAfterCommit(toCreator.capture());
        assertThat(toCreator.getAllValues()).filteredOn(m -> m.to().equals("owner@example.com")).singleElement()
                .satisfies(m -> {
                    assertThat(m.subject()).isEqualTo("Paid: Hearth Foods wants a video from you");
                    assertThat(m.text()).contains("₹1,200 paid upfront").contains("A 30s launch film for our new ghee");
                    assertThat(m.html()).contains("Open the brief").contains("A2 Desi Ghee");
                });
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM lead_brand_inquiry WHERE id = ?", Boolean.class, submitted.id()))
                .isTrue();
    }

    @Test
    void whenBriefsAreDown_theRequestWaits_andTheCreatorConvertsItLater_atTheirOwnUpfrontShare() {
        UUID brand = signedInBrand("later@hearth.example");
        jdbc.update("UPDATE creator_public_profile SET request_prepayment_percent = 25 WHERE tenant_id = ?", creator);
        var submitted = inquiries.submit(brand, "riya-motion", new SubmitInquiryRequest(null, null, null, null, "A reel", null, null, null, null,
                null, null, null));
        assertThat(submitted.status()).isEqualTo(BrandInquiry.Status.NEW);
        assertThat(submitted.briefUrl()).isNull();
        // The creator has to act, so they hear now.
        verify(mailer).sendAfterCommit(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("owner@example.com")
                && m.subject().equals("Hearth Foods asked you for a video") && m.html().contains("Make the brief")));

        org.mockito.Mockito.reset(briefClient);
        when(briefClient.createBrief(eq(creator), anyString(), any(), any(), any(), any(), eq(new CreativePlanningBriefClient.Pricing(null, 25))))
                .thenReturn(new CreativePlanningBriefClient.CreatedBrief(UUID.randomUUID(), "share-later", null, null, null));
        assertThat(inquiries.convert(creator, submitted.id()).briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/share-later");
    }

    @Test
    void attachmentsMustBeSmallImages_andNothingIsSavedWhenOneIsRefused() {
        UUID brand = signedInBrand("img@hearth.example");
        var big = new org.springframework.mock.web.MockMultipartFile("productImages", "big.png", "image/png", new byte[3 * 1024 * 1024 + 1]);
        var pdf = new org.springframework.mock.web.MockMultipartFile("referenceImages", "deck.pdf", "application/pdf", new byte[]{1});
        var request = new SubmitInquiryRequest(null, null, null, null, "Hi", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> inquiries.submit(brand, "riya-motion", request, java.util.List.of(big), java.util.List.of()))
                .hasMessageContaining("3 MB");
        assertThatThrownBy(() -> inquiries.submit(brand, "riya-motion", request, java.util.List.of(), java.util.List.of(pdf)))
                .hasMessageContaining("Only images");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_brand_inquiry", Integer.class)).isZero();
    }

    @Test
    void aCreatorCannotActOnAnotherCreatorsRequest() {
        UUID brand = signedInBrand("x@hearth.example");
        var submitted = inquiries.submit(brand, "riya-motion", new SubmitInquiryRequest(null, null, null, null, "Hello", null, null, null, null, null, null, null));
        assertThatThrownBy(() -> inquiries.convert(UUID.randomUUID(), submitted.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(inquiries.forCreator(creator).get(0).status()).isEqualTo(BrandInquiry.Status.NEW);
    }

    @Test
    void aTamperedSessionIsRejected() {
        UUID brand = signedInBrand("t@hearth.example");
        String token = sessions.issue(brand).token();
        String forged = token.replace(brand.toString(), UUID.randomUUID().toString());
        assertThat(sessions.resolve(forged)).isEmpty();
        clock.set(NOW.plus(Duration.ofDays(31)));
        assertThat(sessions.resolve(token)).isEmpty();
    }

    private UUID signedInBrand(String email) {
        accounts.requestSignIn(signIn(email, null));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer, atLeastOnce()).sendAfterCommit(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        return sessions.resolve(accounts.completeSignIn(token).session().token()).orElseThrow();
    }

    private static SignInRequest signIn(String email, String pendingAction) {
        return new SignInRequest(email, "Asha", "Hearth Foods", "https://hearth.example", ShowcaseIndustry.FOOD_BEVERAGE,
                "in", false, pendingAction);
    }
}
