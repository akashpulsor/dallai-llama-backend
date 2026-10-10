package com.dalai.llama.tenant.leadmanagement;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.brand.BrandAccountService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignInRequest;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.SignedIn;
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
@Import({BrandAccountService.class, BrandSessionService.class, BrandInquiryService.class, InquiryConvertedRequestCounter.class,
        FollowService.class, ShowcaseEngagementService.class, ShowcasePlayStore.class, CreatorProfileService.class,
        HandlePolicy.class, BrandEngagementDbTest.TestConfig.class})
class BrandEngagementDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties({BrandProperties.class, ShowcaseProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    @MockBean private PlatformMailer mailer;
    @MockBean private CreativePlanningBriefClient briefClient;
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
    }

    @Test
    void aSignInLinkWorksOnceAndGivesASessionForTheBrand() {
        accounts.requestSignIn(signIn("Buyer@Hearth.example ", "follow:riya-motion"));

        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("buyer@hearth.example");
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        // Only the hash is stored.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_brand_sign_in WHERE token_hash = ?", Integer.class,
                PublicTokens.sha256Hex(token))).isEqualTo(1);

        SignedIn signedIn = accounts.completeSignIn(token);
        assertThat(signedIn.pendingAction()).isEqualTo("follow:riya-motion");
        assertThat(signedIn.brand().companyName()).isEqualTo("Hearth Foods");
        assertThat(sessions.resolve(signedIn.sessionToken())).isPresent();
        assertThatThrownBy(() -> accounts.completeSignIn(token)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredLinkDoesNotSignIn() {
        accounts.requestSignIn(signIn("late@hearth.example", null));
        ArgumentCaptor<PlatformMailer.Mail> mail = ArgumentCaptor.forClass(PlatformMailer.Mail.class);
        verify(mailer).send(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");

        clock.set(NOW.plus(Duration.ofMinutes(31)));
        assertThatThrownBy(() -> accounts.completeSignIn(token)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signInLinksAreRateLimitedPerHourWithoutTellingTheCaller() {
        for (int i = 0; i < 7; i++) accounts.requestSignIn(signIn("busy@hearth.example", null));
        verify(mailer, org.mockito.Mockito.times(5)).send(any());
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
    void aRequestReachesTheCreatorAndBecomesABriefTheBrandIsSent() {
        UUID brand = signedInBrand("ask@hearth.example");
        clearInvocations(mailer);
        var submitted = inquiries.submit(brand, "riya-motion", new SubmitInquiryRequest("pub0000001", null,
                BrandInquiry.BudgetBand.FROM_50K_TO_2L, BrandInquiry.Timeline.THIS_MONTH, "A 30s launch film for our new ghee"));

        // The creator was alerted, and the film counted the brand's interest.
        verify(mailer).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("owner@example.com")));
        assertThat(jdbc.queryForObject("SELECT inquiry_count FROM showcase_item WHERE public_id = 'pub0000001'", Integer.class))
                .isEqualTo(1);
        CreatorInquiryView seen = inquiries.forCreator(creator).get(0);
        assertThat(seen.filmTitle()).isEqualTo("Video film0000001");
        assertThat(seen.brand().companyName()).isEqualTo("Hearth Foods");

        UUID requirement = UUID.randomUUID();
        when(briefClient.createBrief(eq(creator), anyString(), eq("Hearth Foods"), eq("food beverage")))
                .thenReturn(new CreativePlanningBriefClient.CreatedBrief(requirement, "share-abc"));
        clearInvocations(mailer);
        CreatorInquiryView converted = inquiries.convert(creator, submitted.id());

        assertThat(converted.status()).isEqualTo(BrandInquiry.Status.CONVERTED);
        assertThat(converted.briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/share-abc");
        verify(mailer).send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equals("ask@hearth.example")
                && m.text().contains("https://creator.dalaillama.in/brief/share-abc")));
        assertThat(inquiries.forBrand(brand).get(0).briefUrl()).isEqualTo("https://creator.dalaillama.in/brief/share-abc");
        assertThat(convertedCounter.convertedRequests(creator)).isEqualTo(1);
        assertThatThrownBy(() -> inquiries.convert(creator, submitted.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCreatorCannotActOnAnotherCreatorsRequest() {
        UUID brand = signedInBrand("x@hearth.example");
        var submitted = inquiries.submit(brand, "riya-motion", new SubmitInquiryRequest(null, null, null, null, "Hello"));
        assertThatThrownBy(() -> inquiries.convert(UUID.randomUUID(), submitted.id())).isInstanceOf(IllegalArgumentException.class);
        verify(briefClient, never()).createBrief(any(), any(), any(), any());
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
        verify(mailer, atLeastOnce()).send(mail.capture());
        String token = mail.getValue().text().replaceAll("(?s).*/brands/sign-in/(\\S+).*", "$1");
        return sessions.resolve(accounts.completeSignIn(token).sessionToken()).orElseThrow();
    }

    private static SignInRequest signIn(String email, String pendingAction) {
        return new SignInRequest(email, "Asha", "Hearth Foods", "https://hearth.example", ShowcaseIndustry.FOOD_BEVERAGE,
                "in", false, pendingAction);
    }
}
