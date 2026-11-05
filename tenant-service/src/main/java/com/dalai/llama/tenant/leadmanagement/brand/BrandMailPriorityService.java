package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.leadmanagement.outreach.MailThemes;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** What a brand wants mail about (V77), from its console: product themes, which steer its weekly videos,
 * and companies whose ads it would like to see, which ops find videos for. */
@Service
@RequiredArgsConstructor
public class BrandMailPriorityService {

    private static final int MAX_ADS_FROM = 500;

    public record MailPriorities(List<String> themes, String adsFrom) {
    }

    /** A brand's priorities, for ops to find videos by. */
    public record BrandPriorities(String email, String company, List<String> themes, String adsFrom, OffsetDateTime updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public MailPriorities get(UUID brandContactId) {
        return jdbc.query("SELECT themes, ads_from FROM lead_brand_mail_priority WHERE brand_contact_id = ?",
                (rs, n) -> new MailPriorities(themes(rs.getString(1)), rs.getString(2)), brandContactId)
                .stream().findFirst().orElse(new MailPriorities(List.of(), null));
    }

    @Transactional
    public MailPriorities save(UUID brandContactId, MailPriorities priorities) {
        List<String> themes = priorities.themes() == null ? List.of() : priorities.themes().stream().distinct().toList();
        if (!MailThemes.keys().containsAll(themes)) throw new IllegalArgumentException("That isn't a kind of video we know");
        String adsFrom = priorities.adsFrom() == null || priorities.adsFrom().isBlank() ? null : priorities.adsFrom().trim();
        if (adsFrom != null && adsFrom.length() > MAX_ADS_FROM) throw new IllegalArgumentException("Keep the companies to " + MAX_ADS_FROM + " characters");
        jdbc.update("""
                INSERT INTO lead_brand_mail_priority (brand_contact_id, themes, ads_from) VALUES (?, ?, ?)
                ON CONFLICT (brand_contact_id) DO UPDATE SET themes = EXCLUDED.themes, ads_from = EXCLUDED.ads_from, updated_at = NOW()""",
                brandContactId, String.join(",", themes), adsFrom);
        return get(brandContactId);
    }

    /** Every brand's priorities, latest first. */
    public List<BrandPriorities> all() {
        return jdbc.query("""
                SELECT c.email, c.company_name, p.themes, p.ads_from, p.updated_at
                FROM lead_brand_mail_priority p JOIN lead_brand_contact c ON c.id = p.brand_contact_id
                ORDER BY p.updated_at DESC""",
                (rs, n) -> new BrandPriorities(rs.getString(1), rs.getString(2), themes(rs.getString(3)), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class)));
    }

    private static List<String> themes(String stored) {
        return stored.isBlank() ? List.of() : Arrays.asList(stored.split(","));
    }
}
