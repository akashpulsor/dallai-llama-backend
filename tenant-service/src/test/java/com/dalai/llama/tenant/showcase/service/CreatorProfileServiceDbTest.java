package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ProfileChecklistItem;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.dto.MyPublicProfileView;
import com.dalai.llama.tenant.showcase.dto.UpdatePublicProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs every Flyway migration (V1..V26) against a real Postgres, validates the JPA mapping
 * against it ({@code ddl-auto=validate}) and exercises handle allocation, handle changes and
 * the release window on real unique constraints. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CreatorProfileService.class, HandlePolicy.class, CreatorProfileServiceDbTest.TestConfig.class})
class CreatorProfileServiceDbTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    @EnableConfigurationProperties(ShowcaseProperties.class)
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-10-09T10:00:00Z"));
        }
    }

    @Autowired private CreatorProfileService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    @BeforeEach
    void clean() {
        clock.set(Instant.parse("2026-10-09T10:00:00Z"));
        jdbc.update("DELETE FROM creator_handle_history");
        jdbc.update("DELETE FROM creator_profile_industry");
        jdbc.update("DELETE FROM creator_public_profile");
    }

    @Test
    void ensureProfile_generatesAHandleFromTheNameAndIsIdempotent() {
        UUID tenant = newTenant();

        CreatorPublicProfile first = service.ensureProfile(tenant, "Riya Motion");
        CreatorPublicProfile again = service.ensureProfile(tenant, "Someone Else");

        assertThat(first.getHandle()).isEqualTo("riya-motion");
        assertThat(first.getDisplayName()).isEqualTo("Riya Motion");
        assertThat(first.getStatus()).isEqualTo(ProfileStatus.ACTIVE);
        assertThat(again.getHandle()).isEqualTo("riya-motion");
        assertThat(again.getDisplayName()).isEqualTo("Riya Motion");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_public_profile", Integer.class)).isEqualTo(1);
    }

    @Test
    void ensureProfile_numbersClashingHandlesAndFallsBackForUnusableNames() {
        assertThat(service.ensureProfile(newTenant(), "Aarav Studio").getHandle()).isEqualTo("aarav-studio");
        assertThat(service.ensureProfile(newTenant(), "Aarav  Studio!").getHandle()).isEqualTo("aarav-studio-2");
        assertThat(service.ensureProfile(newTenant(), "Support").getHandle()).isEqualTo("creator");
        assertThat(service.ensureProfile(newTenant(), null).getHandle()).isEqualTo("creator-2");
    }

    @Test
    void findMine_listsWhatIsMissing_andUpdateRoundTripsIndustries() {
        UUID tenant = newTenant();
        service.ensureProfile(tenant, "Sana Reels");

        assertThat(service.findMine(tenant)).get()
                .extracting(MyPublicProfileView::missing).asList()
                .containsExactly(ProfileChecklistItem.YOUTUBE_CHANNEL, ProfileChecklistItem.SHOWCASE_PICKS,
                        ProfileChecklistItem.AVATAR, ProfileChecklistItem.HEADLINE, ProfileChecklistItem.INDUSTRY);

        MyPublicProfileView updated = service.update(tenant, request("sana-reels", "Beverage ads that sell",
                Set.of(ShowcaseIndustry.FOOD_BEVERAGE, ShowcaseIndustry.ECOMMERCE)));

        assertThat(updated.industries()).containsExactlyInAnyOrder(ShowcaseIndustry.FOOD_BEVERAGE, ShowcaseIndustry.ECOMMERCE);
        assertThat(updated.missing()).containsExactly(
                ProfileChecklistItem.YOUTUBE_CHANNEL, ProfileChecklistItem.SHOWCASE_PICKS, ProfileChecklistItem.AVATAR);
        assertThat(updated.publicUrl()).isEqualTo("https://dalaillama.in/c/sana-reels");
        assertThat(service.findMine(tenant).orElseThrow().industries())
                .containsExactlyInAnyOrder(ShowcaseIndustry.FOOD_BEVERAGE, ShowcaseIndustry.ECOMMERCE);
    }

    @Test
    void update_rejectsMoreIndustriesThanAllowed() {
        UUID tenant = newTenant();
        service.ensureProfile(tenant, "Kabir Frames");

        assertThatThrownBy(() -> service.update(tenant, request("kabir-frames", null,
                Set.of(ShowcaseIndustry.FASHION, ShowcaseIndustry.BEAUTY, ShowcaseIndustry.TECH, ShowcaseIndustry.TRAVEL))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleChange_honoursTheCooldownAndReservesTheOldHandle() {
        UUID owner = newTenant();
        UUID other = newTenant();
        service.ensureProfile(owner, "Riya Motion");
        service.ensureProfile(other, "Someone");

        service.update(owner, request("riya-films", null, Set.of()));

        // The released handle stays with its owner for the redirect window.
        assertThat(service.checkHandle(other, "riya-motion").reason()).isEqualTo(CreatorProfileService.TAKEN);
        assertThat(service.checkHandle(owner, "riya-motion").available()).isTrue();

        // A second change inside the cooldown is refused, with the date it opens again.
        assertThatThrownBy(() -> service.update(owner, request("riya-again", null, Set.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.findMine(owner).orElseThrow().handleChangeAllowedFrom()).isNotNull();

        // After the cooldown the owner can take their old handle back.
        clock.advance(Duration.ofDays(31));
        assertThat(service.update(owner, request("riya-motion", null, Set.of())).handle()).isEqualTo("riya-motion");

        // And once the redirect window passes, a released handle is free for anyone.
        clock.advance(Duration.ofDays(31));
        service.update(owner, request("riya-studio", null, Set.of()));
        clock.advance(Duration.ofDays(91));
        assertThat(service.checkHandle(other, "riya-motion").available()).isTrue();
    }

    @Test
    void checkHandle_normalisesInputAndReportsFormatProblems() {
        UUID tenant = newTenant();
        service.ensureProfile(tenant, "Meera");

        assertThat(service.checkHandle(tenant, "  My Brand Films ").handle()).isEqualTo("my-brand-films");
        assertThat(service.checkHandle(tenant, "ab").reason()).isEqualTo("TOO_SHORT");
        assertThat(service.checkHandle(tenant, "creators").reason()).isEqualTo("RESERVED");
    }

    private UpdatePublicProfileRequest request(String handle, String headline, Set<ShowcaseIndustry> industries) {
        return new UpdatePublicProfileRequest(handle, "Display Name", headline, null, "in", null, industries, true);
    }

    private UUID newTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, primary_contact_name, primary_contact_email) VALUES (?, ?, ?, ?, ?)",
                id, "Tenant " + id, "t-" + id.toString().substring(0, 8), "Owner", "owner@example.com");
        return id;
    }
}
