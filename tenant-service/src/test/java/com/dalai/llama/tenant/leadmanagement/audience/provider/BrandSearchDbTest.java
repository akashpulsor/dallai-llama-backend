package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.LeadView;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceService;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.showcase.MutableClock;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.showcase.service.HandlePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule 42 on a real Postgres with Hunter.io stubbed: a search reaches the provider once and is then
 * served from the shared directory; a company's emails are looked up once (credits) and reused by
 * every creator; the monthly credit cap stops lookups but never cached results. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "outreach.hash-pepper=pepper",
        "audience-provider.hunter.api-key=test-key", "audience-provider.hunter.monthly-credits=5",
        "audience-provider.hunter.emails-per-company=2"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({BrandSearchService.class, BrandSearchStore.class, HunterClient.class, AudienceStore.class, AudienceService.class,
        RecipientHasher.class, CreatorProfileService.class, HandlePolicy.class, BrandSearchDbTest.TestConfig.class})
class BrandSearchDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final HttpServer HUNTER = stub();
    private static final AtomicInteger DISCOVERS = new AtomicInteger();
    private static final AtomicInteger DOMAIN_SEARCHES = new AtomicInteger();
    private static final List<String> DISCOVER_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> API_KEYS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("audience-provider.hunter.base-url", () -> "http://localhost:" + HUNTER.getAddress().getPort() + "/v2");
    }

    @TestConfiguration
    @EnableConfigurationProperties({HunterProperties.class, OutreachProperties.class, ShowcaseProperties.class})
    static class TestConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private BrandSearchService brands;
    @Autowired private AudienceService audienceService;
    @Autowired private CreatorProfileService profileService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private UUID riya;
    private UUID arjun;

    @BeforeEach
    void seed() {
        clock.set(NOW);
        wipe(jdbc);
        riya = tenant(jdbc, "Riya");
        arjun = tenant(jdbc, "Arjun");
        profileService.ensureProfile(riya, "Riya");
        profileService.ensureProfile(arjun, "Arjun");
        DISCOVERS.set(0);
        DOMAIN_SEARCHES.set(0);
        DISCOVER_BODIES.clear();
        API_KEYS.clear();
    }

    @AfterAll
    static void stop() {
        HUNTER.stop(0);
    }

    @Test
    void aSearchReachesHunterOnceThenEveryoneGetsTheSharedDirectory() {
        BrandSearchService.SearchResult first = brands.search(new BrandSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "in", "tea"));
        assertThat(first.fromDirectory()).isFalse();
        assertThat(first.companies()).extracting(BrandSearchService.CompanyView::domain).containsExactly("hearthfoods.example", "chaico.example");
        assertThat(DISCOVER_BODIES.get(0)).contains("\"query\":\"tea\"", "\"Food and Beverage Services\"", "\"country\":\"IN\"");
        assertThat(API_KEYS).containsOnly("test-key");

        BrandSearchService.SearchResult again = brands.search(new BrandSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "IN", "  Tea "));
        assertThat(again.fromDirectory()).isTrue();
        assertThat(again.companies()).hasSize(2);
        assertThat(DISCOVERS.get()).isEqualTo(1);

        clock.set(NOW.plus(Duration.ofDays(31)));
        brands.search(new BrandSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "IN", "tea"));
        assertThat(DISCOVERS.get()).isEqualTo(2);
    }

    @Test
    void emailsAreLookedUpOnceAndReusedByTheNextCreator() {
        UUID riyaList = audienceService.create(riya, "Tea brands").id();
        UUID arjunList = audienceService.create(arjun, "Food").id();
        List<UUID> ids = brands.search(new BrandSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "IN", "tea")).companies().stream()
                .map(BrandSearchService.CompanyView::id).toList();

        BrandSearchService.AddResult riyaAdd = brands.addToAudience(riya, riyaList, new BrandSearchService.AddRequest(List.of(ids.get(0))));
        assertThat(riyaAdd.companiesAdded()).isEqualTo(1);
        assertThat(riyaAdd.contactsAdded()).isEqualTo(2);
        assertThat(riyaAdd.credits().usedThisMonth()).isEqualTo(2);
        LeadView lead = audienceService.leads(riya, riyaList, null, 0).leads().get(0);
        assertThat(lead.company()).isEqualTo("Hearth Foods");
        assertThat(lead.name()).isEqualTo("Asha Rao");
        assertThat(lead.designation()).isEqualTo("Marketing Head");
        // Hunter verified Asha's address, so (rule 25) only the validated one shows; Ravi's is kept behind it.
        assertThat(lead.emails()).singleElement().satisfies(e -> {
            assertThat(e.value()).isEqualTo("asha@hearthfoods.example");
            assertThat(e.status()).isEqualTo("LIKELY_VALID");
        });

        BrandSearchService.AddResult arjunAdd = brands.addToAudience(arjun, arjunList, new BrandSearchService.AddRequest(List.of(ids.get(0))));
        assertThat(arjunAdd.contactsAdded()).isEqualTo(2);
        assertThat(DOMAIN_SEARCHES.get()).isEqualTo(1);
        assertThat(arjunAdd.credits().usedThisMonth()).isEqualTo(2);
        // Each creator gets their own private lead.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lead_creator_lead", Integer.class)).isEqualTo(2);
    }

    @Test
    void theMonthlyCreditCapStopsNewLookupsButNotCachedOnes() {
        UUID list = audienceService.create(riya, "All").id();
        List<UUID> ids = brands.search(new BrandSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, null, null)).companies().stream()
                .map(BrandSearchService.CompanyView::id).toList();
        jdbc.update("INSERT INTO lead_provider_usage (usage_month, provider, credits_used, calls) VALUES ('2026-10', 'HUNTER', 4, 2)");

        BrandSearchService.AddResult result = brands.addToAudience(riya, list, new BrandSearchService.AddRequest(ids));
        assertThat(result.skippedNoCredits()).isEqualTo(2);
        assertThat(result.companiesAdded()).isZero();
        assertThat(DOMAIN_SEARCHES.get()).isZero();
    }

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v2/discover", e -> {
                API_KEYS.add(e.getRequestHeaders().getFirst("X-API-KEY"));
                DISCOVER_BODIES.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                DISCOVERS.incrementAndGet();
                reply(e, "{\"data\":[{\"domain\":\"hearthfoods.example\",\"organization\":\"Hearth Foods\",\"emails_count\":{\"total\":12}},"
                        + "{\"domain\":\"chaico.example\",\"organization\":\"Chai Co\",\"emails_count\":{\"total\":3}}],\"meta\":{\"results\":2}}");
            });
            server.createContext("/v2/domain-search", e -> {
                DOMAIN_SEARCHES.incrementAndGet();
                reply(e, "{\"data\":{\"domain\":\"hearthfoods.example\",\"emails\":["
                        + "{\"value\":\"Asha@HearthFoods.example\",\"type\":\"personal\",\"confidence\":96,\"first_name\":\"Asha\",\"last_name\":\"Rao\","
                        + "\"position\":\"Marketing Head\",\"verification\":{\"status\":\"valid\"}},"
                        + "{\"value\":\"ravi@hearthfoods.example\",\"type\":\"personal\",\"confidence\":80,\"first_name\":\"Ravi\",\"last_name\":null,"
                        + "\"position\":null,\"verification\":{\"status\":\"accept_all\"}}]}}");
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void reply(HttpExchange e, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "application/json");
        e.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
        e.close();
    }
}
