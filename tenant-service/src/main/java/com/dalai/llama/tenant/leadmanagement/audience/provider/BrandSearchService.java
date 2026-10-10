package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues;
import com.dalai.llama.tenant.leadmanagement.audience.provider.BrandSearchStore.Company;
import com.dalai.llama.tenant.leadmanagement.audience.provider.BrandSearchStore.CompanyContact;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Set;
import java.util.UUID;

/** Rule 42: a shared brand directory filled from Hunter.io. Creators search by industry, country
 * and keywords; a search reaches the provider only when nobody ran it in the last
 * {@code searchCacheDays} (company search is free). Adding companies to an audience looks up their
 * emails once (credits) and keeps them for every creator after; the monthly credit cap stops
 * lookups, never cached results. Provider results become a creator's leads only on "add"
 * (AUDIENCE_PROVIDER §14), and then follow every outreach rule like uploaded leads. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandSearchService {

    static final String PROVIDER = "HUNTER";
    static final int MAX_ADD = 25;

    private final HunterProperties properties;
    private final HunterClient hunter;
    private final BrandSearchStore store;
    private final AudienceStore audiences;
    private final RecipientHasher hasher;
    private final Clock clock;

    public record SearchRequest(@NotNull ShowcaseIndustry industry, @Size(max = 2) String countryCode, @Size(max = 120) String query) {
    }

    public record CompanyView(UUID id, String name, String domain, ShowcaseIndustry industry, String countryCode, Integer emailsAvailable,
                              int contactsKnown, boolean contactsLookedUp) {
    }

    public record Credits(boolean configured, int monthlyCredits, int usedThisMonth, int leftThisMonth) {
    }

    public record SearchResult(List<CompanyView> companies, boolean fromDirectory, Credits credits) {
    }

    public record AddRequest(@NotEmpty @Size(max = MAX_ADD) List<UUID> companyIds) {
    }

    public record AddResult(int companiesAdded, int contactsAdded, int companiesWithoutContacts, int skippedNoCredits, Credits credits) {
    }

    @Transactional
    public SearchResult search(SearchRequest r) {
        String country = r.countryCode() == null || r.countryCode().isBlank() ? null : r.countryCode().trim().toUpperCase(Locale.ROOT);
        String query = blankToNull(r.query());
        String key = PublicTokens.sha256Hex(PROVIDER + "|" + r.industry() + "|" + country + "|"
                + (query == null ? "" : query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")));
        Instant now = clock.instant();
        if (store.freshSearch(key, now.minus(Duration.ofDays(properties.searchCacheDays())))) {
            return new SearchResult(views(store.searchResults(key)), true, credits());
        }
        if (!properties.configured()) {
            // No provider yet: serve what the directory already knows.
            return new SearchResult(views(store.known(r.industry(), country, 50)), true, credits());
        }
        List<HunterClient.DiscoveredCompany> found = hunter.discover(properties.industries().getOrDefault(r.industry(), List.of()), country, query);
        List<UUID> ids = new ArrayList<>();
        for (HunterClient.DiscoveredCompany c : found) {
            if (c.domain() == null || c.domain().isBlank()) continue;
            ids.add(store.upsertCompany(c.domain(), c.organization(), r.industry(), country, c.emailsTotal(), PROVIDER));
        }
        store.saveSearch(key, PROVIDER, describe(r.industry(), country, query), ids);
        return new SearchResult(views(store.searchResults(key)), false, credits());
    }

    @Transactional
    public AddResult addToAudience(UUID tenantId, UUID audienceId, AddRequest request) {
        if (!audiences.owns(tenantId, audienceId)) throw new IllegalArgumentException("Unknown audience");
        Instant now = clock.instant();
        int added = 0, contactsAdded = 0, without = 0, skipped = 0;
        for (Company company : store.companies(new LinkedHashSet<>(request.companyIds()))) {
            List<CompanyContact> contacts = store.contacts(company.id());
            boolean stale = company.contactsFetchedAt() == null
                    || company.contactsFetchedAt().isBefore(now.minus(Duration.ofDays(properties.contactsCacheDays())));
            if (contacts.isEmpty() && stale) {
                if (!properties.configured() || credits().leftThisMonth() < properties.emailsPerCompany()) {
                    skipped++;
                    continue;
                }
                fetchContacts(company, now);
                contacts = store.contacts(company.id());
            }
            if (contacts.isEmpty()) {
                without++;
                continue;
            }
            Set<UUID> points = new LinkedHashSet<>();
            contacts.forEach(c -> points.add(c.contactPointId()));
            List<UUID> owners = audiences.leadsOwning(tenantId, points);
            UUID lead;
            if (owners.isEmpty()) {
                lead = audiences.createLead(tenantId, null, company.name(), company.industry(), "https://" + company.domain(), now);
            } else {
                lead = owners.get(0);
                if (owners.size() > 1) audiences.merge(tenantId, lead, owners.subList(1, owners.size()));
                audiences.fillBlanks(lead, null, company.name(), company.industry(), "https://" + company.domain());
            }
            for (UUID point : points) if (audiences.link(tenantId, lead, point)) contactsAdded++;
            audiences.addMember(audienceId, lead);
            added++;
        }
        return new AddResult(added, contactsAdded, without, skipped, credits());
    }

    public Credits credits() {
        int used = store.creditsUsed(month(), PROVIDER);
        return new Credits(properties.configured(), properties.monthlyCredits(), used, Math.max(0, properties.monthlyCredits() - used));
    }

    private void fetchContacts(Company company, Instant now) {
        List<HunterClient.FoundEmail> emails = hunter.domainSearch(company.domain(), properties.emailsPerCompany());
        store.recordUsage(month(), PROVIDER, emails.size());
        for (HunterClient.FoundEmail e : emails) {
            String value = ContactPointValues.normaliseEmail(e.value());
            if (!ContactPointValues.validEmail(value)) continue;
            // Provider status is evidence, not truth (AUDIENCE_PROVIDER §20): "valid" counts as likely valid.
            String status = "valid".equals(e.verificationStatus()) ? "LIKELY_VALID" : "UNVERIFIED";
            UUID point = audiences.upsertContactPoint(ContactPointValues.Kind.EMAIL, value, hasher.configured() ? hasher.hash(value) : null,
                    status, "PROVIDER_" + (e.verificationStatus() == null ? "unknown" : e.verificationStatus()).toUpperCase(Locale.ROOT));
            String name = ((e.firstName() == null ? "" : e.firstName()) + " " + (e.lastName() == null ? "" : e.lastName())).trim();
            store.linkContact(company.id(), point, name.isEmpty() ? null : name, e.position(), e.confidence(), e.verificationStatus());
        }
        store.markContactsFetched(company.id(), now);
    }

    private List<CompanyView> views(List<Company> companies) {
        return companies.stream().map(c -> new CompanyView(c.id(), c.name(), c.domain(), c.industry(), c.countryCode(), c.emailsAvailable(),
                c.contactsKnown(), c.contactsFetchedAt() != null)).toList();
    }

    private String month() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    private static String describe(ShowcaseIndustry industry, String country, String query) {
        String d = industry + (country == null ? "" : " in " + country) + (query == null ? "" : " · " + query);
        return d.length() > 300 ? d.substring(0, 300) : d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
