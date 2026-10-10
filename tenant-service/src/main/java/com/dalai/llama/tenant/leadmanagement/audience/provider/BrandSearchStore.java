package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** The shared brand directory (V36): companies, their known contacts, cached searches, credits. */
@Repository
@RequiredArgsConstructor
public class BrandSearchStore {

    public record Company(UUID id, String domain, String name, ShowcaseIndustry industry, String countryCode, Integer emailsAvailable,
                          Instant contactsFetchedAt, int contactsKnown) {
    }

    public record CompanyContact(UUID contactPointId, String email, String fullName, String position) {
    }

    private static final String COMPANY = """
            SELECT c.id, c.domain, c.name, c.industry, c.country_code, c.emails_available, c.contacts_fetched_at,
                   (SELECT COUNT(*) FROM lead_company_contact cc WHERE cc.company_id = c.id)
            FROM lead_company c""";
    private static final RowMapper<Company> ROW = (rs, n) -> new Company(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
            rs.getString(4) == null ? null : ShowcaseIndustry.valueOf(rs.getString(4)), rs.getString(5), (Integer) rs.getObject(6),
            rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getInt(8));

    private final JdbcTemplate jdbc;

    public boolean freshSearch(String queryKey, Instant notBefore) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM lead_provider_search WHERE query_key = ? AND searched_at > ?)",
                Boolean.class, queryKey, Timestamp.from(notBefore)));
    }

    public List<Company> searchResults(String queryKey) {
        return jdbc.query(COMPANY + " JOIN lead_provider_search_result r ON r.company_id = c.id WHERE r.query_key = ? ORDER BY r.rank",
                ROW, queryKey);
    }

    /** Companies already in the directory for an industry/country, best known first: serves
     * searches the provider can't (no key, out of credits) from what earlier searches collected. */
    public List<Company> known(ShowcaseIndustry industry, String countryCode, int limit) {
        return jdbc.query(COMPANY + " WHERE (?::text IS NULL OR c.industry = ?) AND (?::text IS NULL OR c.country_code = ?)"
                        + " ORDER BY c.contacts_fetched_at DESC NULLS LAST, c.emails_available DESC NULLS LAST LIMIT ?",
                ROW, industry == null ? null : industry.name(), industry == null ? null : industry.name(), countryCode, countryCode, limit);
    }

    public UUID upsertCompany(String domain, String name, ShowcaseIndustry industry, String countryCode, int emailsAvailable, String provider) {
        return jdbc.queryForObject("""
                INSERT INTO lead_company (id, domain, name, industry, country_code, emails_available, provider)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (domain) DO UPDATE SET name = COALESCE(EXCLUDED.name, lead_company.name),
                    industry = COALESCE(lead_company.industry, EXCLUDED.industry),
                    country_code = COALESCE(lead_company.country_code, EXCLUDED.country_code),
                    emails_available = EXCLUDED.emails_available, last_seen_at = NOW()
                RETURNING id""", UUID.class, UUID.randomUUID(), domain.toLowerCase(), name,
                industry == null ? null : industry.name(), countryCode == null ? null : countryCode.toUpperCase(), emailsAvailable, provider);
    }

    public void saveSearch(String queryKey, String provider, String description, List<UUID> companyIds) {
        jdbc.update("DELETE FROM lead_provider_search WHERE query_key = ?", queryKey);
        jdbc.update("INSERT INTO lead_provider_search (query_key, provider, description, result_count) VALUES (?, ?, ?, ?)",
                queryKey, provider, description, companyIds.size());
        for (int i = 0; i < companyIds.size(); i++) {
            jdbc.update("INSERT INTO lead_provider_search_result (query_key, company_id, rank) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    queryKey, companyIds.get(i), i);
        }
    }

    public List<Company> companies(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        return jdbc.query(COMPANY + " WHERE c.id IN (" + in + ")", ROW, ids.toArray());
    }

    public List<CompanyContact> contacts(UUID companyId) {
        return jdbc.query("""
                SELECT cp.id, cp.value, cc.full_name, cc.position FROM lead_company_contact cc
                JOIN lead_contact_point cp ON cp.id = cc.contact_point_id
                WHERE cc.company_id = ? AND cp.status <> 'INVALID' ORDER BY cc.confidence DESC NULLS LAST""",
                (rs, n) -> new CompanyContact(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), companyId);
    }

    public void linkContact(UUID companyId, UUID contactPointId, String fullName, String position, int confidence, String providerStatus) {
        jdbc.update("""
                INSERT INTO lead_company_contact (company_id, contact_point_id, full_name, position, confidence, provider_status)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", companyId, contactPointId, fullName, position, confidence, providerStatus);
    }

    public void markContactsFetched(UUID companyId, Instant at) {
        jdbc.update("UPDATE lead_company SET contacts_fetched_at = ? WHERE id = ?", Timestamp.from(at), companyId);
    }

    public int creditsUsed(String month, String provider) {
        Integer used = jdbc.queryForObject("SELECT COALESCE(SUM(credits_used), 0) FROM lead_provider_usage WHERE usage_month = ? AND provider = ?",
                Integer.class, month, provider);
        return used == null ? 0 : used;
    }

    public void recordUsage(String month, String provider, int credits) {
        jdbc.update("""
                INSERT INTO lead_provider_usage (usage_month, provider, credits_used, calls) VALUES (?, ?, ?, 1)
                ON CONFLICT (usage_month, provider) DO UPDATE SET credits_used = lead_provider_usage.credits_used + EXCLUDED.credits_used,
                    calls = lead_provider_usage.calls + 1""", month, provider, credits);
    }

    public Optional<Company> company(UUID id) {
        return companies(List.of(id)).stream().findFirst();
    }
}
