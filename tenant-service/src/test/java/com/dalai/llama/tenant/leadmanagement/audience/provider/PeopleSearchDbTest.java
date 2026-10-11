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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rule 44 on a real Postgres with Apollo.io stubbed: search is free and shared; an email is
 * revealed once per person (1 credit) and reused by every creator; past the monthly credits people
 * are still added and the creator enters the email themselves. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "outreach.hash-pepper=pepper",
        "audience-provider.apollo.api-key=apollo-key", "audience-provider.apollo.monthly-credits=1",
        "audience-provider.apollo.per-page=25", "audience-provider.apollo.search-cache-days=30",
        "audience-provider.apollo.default-titles=marketing manager,founder"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PeopleSearchService.class, PeopleDirectoryStore.class, BrandSearchStore.class, ApolloClient.class, AudienceStore.class,
        AudienceService.class, RecipientHasher.class, CreatorProfileService.class, HandlePolicy.class, PeopleSearchDbTest.TestConfig.class})
class PeopleSearchDbTest extends ShowcaseDbTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final HttpServer APOLLO = stub();
    private static final AtomicInteger SEARCHES = new AtomicInteger();
    private static final AtomicInteger MATCHES = new AtomicInteger();
    private static final List<String> SEARCH_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("audience-provider.apollo.base-url", () -> "http://localhost:" + APOLLO.getAddress().getPort() + "/api/v1");
    }

    @TestConfiguration
    @EnableConfigurationProperties({ApolloProperties.class, OutreachProperties.class, ShowcaseProperties.class})
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

    @Autowired private PeopleSearchService people;
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
        SEARCHES.set(0);
        MATCHES.set(0);
        SEARCH_BODIES.clear();
        KEYS.clear();
    }

    @AfterAll
    static void stop() {
        APOLLO.stop(0);
    }

    @Test
    void aSearchReachesApolloOnceWithTheIndustryCountryAndDefaultTitles() {
        PeopleSearchService.SearchResult first = people.search(new PeopleSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "in", null, "tea"));
        assertThat(first.fromDirectory()).isFalse();
        assertThat(first.people()).extracting(PeopleSearchService.PersonView::name).containsExactly("Asha R***o", "Vikram S***h");
        assertThat(first.people()).allSatisfy(p -> assertThat(p.emailKnown()).isFalse());
        assertThat(SEARCH_BODIES.get(0)).contains("\"q_keywords\":\"food beverage tea\"", "\"organization_locations\":[\"India\"]",
                "\"person_titles\":[\"marketing manager\",\"founder\"]");
        assertThat(KEYS).containsOnly("apollo-key");

        assertThat(people.search(new PeopleSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "IN", " ", " Tea ")).fromDirectory()).isTrue();
        assertThat(SEARCHES.get()).isEqualTo(1);
        assertThatThrownBy(() -> people.search(new PeopleSearchService.SearchRequest(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailsAreRevealedOnce_thenCreatorsEnrichTheRestThemselves() {
        UUID riyaList = audienceService.create(riya, "Tea").id();
        UUID arjunList = audienceService.create(arjun, "Food").id();
        List<UUID> ids = people.search(new PeopleSearchService.SearchRequest(ShowcaseIndustry.FOOD_BEVERAGE, "IN", null, null)).people()
                .stream().map(PeopleSearchService.PersonView::id).toList();

        // One credit this month: Asha is revealed, Vikram is added without an email.
        PeopleSearchService.AddResult added = people.addToAudience(riya, riyaList, new PeopleSearchService.AddRequest(ids));
        assertThat(added.added()).isEqualTo(2);
        assertThat(added.withEmail()).isEqualTo(1);
        assertThat(added.needEmail()).isEqualTo(1);
        assertThat(added.credits().leftThisMonth()).isZero();
        assertThat(MATCHES.get()).isEqualTo(1);

        List<LeadView> leads = audienceService.leads(riya, riyaList, null, 0).leads();
        LeadView asha = leads.stream().filter(l -> l.name().equals("Asha Rao")).findFirst().orElseThrow();
        assertThat(asha.designation()).isEqualTo("Marketing Head");
        assertThat(asha.company()).isEqualTo("Hearth Foods");
        assertThat(asha.emails()).singleElement().satisfies(e -> {
            assertThat(e.value()).isEqualTo("asha@hearthfoods.example");
            assertThat(e.status()).isEqualTo("LIKELY_VALID");
        });
        LeadView vikram = leads.stream().filter(l -> l.name().startsWith("Vikram")).findFirst().orElseThrow();
        assertThat(vikram.reachable()).isFalse();

        // The creator finds Vikram's email themselves.
        audienceService.addContactPoint(riya, vikram.id(), " Vikram@BoltMotors.example ");
        assertThat(audienceService.leads(riya, riyaList, null, 0).leads()).filteredOn(l -> l.id().equals(vikram.id())).singleElement()
                .satisfies(l -> assertThat(l.reachable()).isTrue());
        assertThatThrownBy(() -> audienceService.addContactPoint(arjun, vikram.id(), "x@y.example")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audienceService.addContactPoint(riya, vikram.id(), "not an email")).isInstanceOf(IllegalArgumentException.class);

        // Arjun gets Asha's revealed email without another credit.
        PeopleSearchService.AddResult arjunAdded = people.addToAudience(arjun, arjunList, new PeopleSearchService.AddRequest(List.of(ids.get(0))));
        assertThat(arjunAdded.withEmail()).isEqualTo(1);
        assertThat(MATCHES.get()).isEqualTo(1);
    }

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/api/v1/mixed_people/api_search", e -> {
                KEYS.add(e.getRequestHeaders().getFirst("x-api-key"));
                SEARCH_BODIES.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                SEARCHES.incrementAndGet();
                reply(e, "{\"people\":[{\"id\":\"p-asha\",\"first_name\":\"Asha\",\"last_name_obfuscated\":\"R***o\",\"title\":\"Marketing Head\","
                        + "\"has_email\":true,\"organization\":{\"name\":\"Hearth Foods\"}},"
                        + "{\"id\":\"p-vikram\",\"first_name\":\"Vikram\",\"last_name_obfuscated\":\"S***h\",\"title\":\"Brand Manager\","
                        + "\"has_email\":true,\"organization\":{\"name\":\"Bolt Motors\"}}]}");
            });
            server.createContext("/api/v1/people/match", e -> {
                MATCHES.incrementAndGet();
                reply(e, "{\"person\":{\"id\":\"p-asha\",\"first_name\":\"Asha\",\"last_name\":\"Rao\",\"title\":\"Marketing Head\","
                        + "\"email\":\"Asha@HearthFoods.example\",\"email_status\":\"verified\","
                        + "\"organization\":{\"name\":\"Hearth Foods\",\"primary_domain\":\"hearthfoods.example\"}}}");
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
