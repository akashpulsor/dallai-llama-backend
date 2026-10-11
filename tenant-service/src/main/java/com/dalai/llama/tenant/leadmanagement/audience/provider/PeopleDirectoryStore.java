package com.dalai.llama.tenant.leadmanagement.audience.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** The shared people directory (V39) and its cached searches. */
@Repository
@RequiredArgsConstructor
public class PeopleDirectoryStore {

    public record Person(UUID id, String providerPersonId, String firstName, String lastNameHint, String fullName, String title,
                         String companyName, String companyDomain, String countryCode, boolean hasEmail, UUID contactPointId,
                         Instant enrichedAt) {
    }

    private static final String SELECT = """
            SELECT p.id, p.provider_person_id, p.first_name, p.last_name_hint, p.full_name, p.title, p.company_name, p.company_domain,
                   p.country_code, COALESCE(p.has_email, false), p.contact_point_id, p.enriched_at
            FROM lead_directory_person p""";
    private static final RowMapper<Person> ROW = (rs, n) -> new Person(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
            rs.getObject(11, UUID.class), rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant());

    private final JdbcTemplate jdbc;

    public UUID upsert(String provider, ApolloClient.Person p, String countryCode) {
        return jdbc.queryForObject("""
                INSERT INTO lead_directory_person (id, provider, provider_person_id, first_name, last_name_hint, title, company_name,
                    company_domain, country_code, has_email)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (provider, provider_person_id) DO UPDATE SET title = COALESCE(EXCLUDED.title, lead_directory_person.title),
                    company_name = COALESCE(EXCLUDED.company_name, lead_directory_person.company_name),
                    company_domain = COALESCE(lead_directory_person.company_domain, EXCLUDED.company_domain),
                    country_code = COALESCE(lead_directory_person.country_code, EXCLUDED.country_code),
                    has_email = EXCLUDED.has_email, last_seen_at = NOW()
                RETURNING id""", UUID.class, UUID.randomUUID(), provider, p.id(), p.firstName(), p.lastNameHint(), p.title(),
                p.companyName(), p.companyDomain(), countryCode, p.hasEmail());
    }

    public void saveResults(String queryKey, List<UUID> personIds) {
        for (int i = 0; i < personIds.size(); i++) {
            jdbc.update("INSERT INTO lead_directory_person_result (query_key, person_id, rank) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    queryKey, personIds.get(i), i);
        }
    }

    public List<Person> results(String queryKey) {
        return jdbc.query(SELECT + " JOIN lead_directory_person_result r ON r.person_id = p.id WHERE r.query_key = ? ORDER BY r.rank",
                ROW, queryKey);
    }

    public List<Person> people(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(SELECT + " WHERE p.id IN (" + ids.stream().map(i -> "?").collect(Collectors.joining(",")) + ")", ROW, ids.toArray());
    }

    public void enriched(UUID personId, UUID contactPointId, String fullName, String title, String companyName, String companyDomain,
                         Instant at) {
        jdbc.update("""
                UPDATE lead_directory_person SET contact_point_id = ?, full_name = COALESCE(?, full_name), title = COALESCE(?, title),
                    company_name = COALESCE(?, company_name), company_domain = COALESCE(?, company_domain), enriched_at = ?
                WHERE id = ?""", contactPointId, fullName, title, companyName, companyDomain, Timestamp.from(at), personId);
    }
}
