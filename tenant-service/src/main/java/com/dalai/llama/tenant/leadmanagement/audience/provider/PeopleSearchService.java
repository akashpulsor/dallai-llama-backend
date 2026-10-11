package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues;
import com.dalai.llama.tenant.leadmanagement.audience.provider.PeopleDirectoryStore.Person;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Rule 44: brand decision makers from Apollo.io, in a shared directory. A search reaches Apollo only
 * when nobody ran it in the last {@code searchCacheDays} (search is free). Adding people to an
 * audience reveals their email once (1 credit) and keeps it for every creator; when this month's
 * credits are spent, the person is still added -- name, title and company -- and the creator enters
 * the email themselves (the lead shows "add email"). Provider data becomes a creator's lead only on
 * "add", and then follows every outreach rule like uploaded leads.
 */
@Service
@RequiredArgsConstructor
public class PeopleSearchService {

    static final String PROVIDER = "APOLLO";
    static final int MAX_ADD = 25;

    private final ApolloProperties properties;
    private final ApolloClient apollo;
    private final PeopleDirectoryStore people;
    private final BrandSearchStore searches;
    private final AudienceStore audiences;
    private final RecipientHasher hasher;
    private final Clock clock;

    public record SearchRequest(ShowcaseIndustry industry, String countryCode, String title, String query) {
    }

    public record PersonView(UUID id, String name, String title, String company, String companyDomain, boolean emailAvailable,
                             boolean emailKnown) {
    }

    public record Credits(boolean configured, int monthlyCredits, int usedThisMonth, int leftThisMonth) {
    }

    public record SearchResult(List<PersonView> people, boolean fromDirectory, Credits credits) {
    }

    public record AddRequest(@NotEmpty @Size(max = MAX_ADD) List<UUID> personIds) {
    }

    /** {@code needEmail}: added without an email (none found, or this month's lookups are used up) --
     * the creator can enter it on the lead. */
    public record AddResult(int added, int withEmail, int needEmail, Credits credits) {
    }

    @Transactional
    public SearchResult search(SearchRequest r) {
        String country = r.countryCode() == null || r.countryCode().isBlank() ? null : r.countryCode().trim().toUpperCase(Locale.ROOT);
        String title = blankToNull(r.title());
        String query = blankToNull(r.query());
        if (r.industry() == null && country == null && title == null && query == null) {
            throw new IllegalArgumentException("Choose an industry or a country, or type a role or keywords");
        }
        String key = PublicTokens.sha256Hex(PROVIDER + "|" + r.industry() + "|" + country + "|" + normal(title) + "|" + normal(query));
        if (searches.freshSearch(key, clock.instant().minus(Duration.ofDays(properties.searchCacheDays())))) {
            return new SearchResult(views(people.results(key)), true, credits());
        }
        if (!properties.peopleConfigured()) return new SearchResult(List.of(), true, credits());

        List<String> titles = title == null ? properties.defaultTitles() : List.of(title);
        String keywords = String.join(" ", nonNull(r.industry() == null ? null : label(r.industry()), query)).trim();
        List<UUID> ids = new ArrayList<>();
        for (ApolloClient.Person p : apollo.searchPeople(titles, keywords, country == null ? null : countryName(country), properties.perPage())) {
            if (p.id() == null || p.id().isBlank()) continue;
            ids.add(people.upsert(PROVIDER, p, country));
        }
        searches.saveSearch(key, PROVIDER, describe(r, country), List.of());
        people.saveResults(key, ids);
        return new SearchResult(views(people.results(key)), false, credits());
    }

    @Transactional
    public AddResult addToAudience(UUID tenantId, UUID audienceId, AddRequest request) {
        if (!audiences.owns(tenantId, audienceId)) throw new IllegalArgumentException("Unknown audience");
        Instant now = clock.instant();
        int added = 0, withEmail = 0, needEmail = 0;
        for (Person person : people.people(new LinkedHashSet<>(request.personIds()))) {
            boolean known = person.contactPointId() != null;
            UUID point = known ? person.contactPointId() : reveal(person, now).orElse(null);
            // A reveal may have filled the full name, title and domain even without an email.
            Person current = known ? person : people.people(List.of(person.id())).get(0);
            UUID lead = null;
            if (point != null) {
                List<UUID> owners = audiences.leadsOwning(tenantId, List.of(point));
                if (!owners.isEmpty()) {
                    lead = owners.get(0);
                    if (owners.size() > 1) audiences.merge(tenantId, lead, owners.subList(1, owners.size()));
                }
            }
            if (lead == null) {
                lead = audiences.createLead(tenantId, displayName(current), current.title(), current.companyName(), null,
                        current.companyDomain() == null ? null : "https://" + current.companyDomain(), now);
            } else {
                audiences.fillBlanks(lead, displayName(current), current.title(), current.companyName(), null,
                        current.companyDomain() == null ? null : "https://" + current.companyDomain());
            }
            if (point != null) {
                audiences.link(tenantId, lead, point);
                withEmail++;
            } else {
                needEmail++;
            }
            audiences.addMember(audienceId, lead);
            added++;
        }
        return new AddResult(added, withEmail, needEmail, credits());
    }

    public Credits credits() {
        int used = searches.creditsUsed(month(), PROVIDER);
        return new Credits(properties.configured(), properties.monthlyCredits(), used, Math.max(0, properties.monthlyCredits() - used));
    }

    /** One credit, only while this month's allowance lasts; never twice for the same person. */
    private Optional<UUID> reveal(Person person, Instant now) {
        if (!properties.configured() || person.enrichedAt() != null || !person.hasEmail() || credits().leftThisMonth() < 1) {
            return Optional.empty();
        }
        Optional<ApolloClient.Enriched> found = apollo.reveal(person.providerPersonId());
        String email = found.map(ApolloClient.Enriched::email).map(ContactPointValues::normaliseEmail).orElse(null);
        UUID point = null;
        if (email != null && ContactPointValues.validEmail(email)) {
            searches.recordUsage(month(), PROVIDER, 1);
            boolean verified = "verified".equals(found.get().emailStatus());
            point = audiences.upsertContactPoint(ContactPointValues.Kind.EMAIL, email, hasher.configured() ? hasher.hash(email) : null,
                    verified ? "LIKELY_VALID" : "UNVERIFIED", "PROVIDER_APOLLO_" + (verified ? "VERIFIED" : "UNVERIFIED"));
        }
        ApolloClient.Enriched e = found.orElse(null);
        String fullName = e == null ? null : (nullToEmpty(e.firstName()) + " " + nullToEmpty(e.lastName())).trim();
        people.enriched(person.id(), point, fullName == null || fullName.isEmpty() ? null : fullName,
                e == null ? null : e.title(), e == null ? null : e.companyName(), e == null ? null : e.companyDomain(), now);
        return Optional.ofNullable(point);
    }

    private List<PersonView> views(List<Person> list) {
        return list.stream().map(p -> new PersonView(p.id(), displayName(p), p.title(), p.companyName(), p.companyDomain(), p.hasEmail(),
                p.contactPointId() != null)).toList();
    }

    private static String displayName(Person p) {
        if (p.fullName() != null) return p.fullName();
        return (nullToEmpty(p.firstName()) + " " + nullToEmpty(p.lastNameHint())).trim();
    }

    static String countryName(String code) {
        String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() || name.equalsIgnoreCase(code) ? null : name;
    }

    private static String label(ShowcaseIndustry industry) {
        return industry.name().replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String month() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    private static String describe(SearchRequest r, String country) {
        String d = (r.industry() == null ? "Any industry" : r.industry().name()) + (country == null ? "" : " in " + country)
                + (blankToNull(r.title()) == null ? "" : " · " + r.title().trim()) + (blankToNull(r.query()) == null ? "" : " · " + r.query().trim());
        return d.length() > 300 ? d.substring(0, 300) : d;
    }

    private static List<String> nonNull(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out;
    }

    private static String normal(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
