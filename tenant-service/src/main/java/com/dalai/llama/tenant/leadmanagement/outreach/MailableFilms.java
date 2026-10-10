package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only films any outreach may carry (rule 9): live platform films the client fully paid for
 * and agreed to marketing use of, on an active profile, not hidden by ops, still playable. */
@Component
@RequiredArgsConstructor
public class MailableFilms {

    public record MailableFilm(UUID itemId, String publicId, String title, String thumbnailUrl, ShowcaseIndustry industry,
                               String clientLabel, UUID tenantId, String handle, String creatorName) {
    }

    private static final String SELECT = """
            SELECT s.id, s.public_id, COALESCE(s.title_override, v.title, 'Film') AS title, v.thumbnail_url, s.industry,
                   s.client_label, s.tenant_id, p.handle, p.display_name
            FROM showcase_item s
            JOIN creator_public_profile p ON p.tenant_id = s.tenant_id
            LEFT JOIN youtube_video v ON v.video_id = s.youtube_video_id
            WHERE s.status = 'LIVE' AND s.origin = 'PLATFORM' AND s.hidden_by_ops = FALSE
              AND s.funded_verified_at IS NOT NULL AND s.marketing_consent_at IS NOT NULL
              AND p.status = 'ACTIVE'
              AND (s.host = 'SELF' OR (v.video_id IS NOT NULL AND v.gone_at IS NULL))
            """;

    private static final RowMapper<MailableFilm> ROW = (rs, n) -> new MailableFilm(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            ShowcaseIndustry.valueOf(rs.getString(5)), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
            rs.getString(9));

    private final JdbcTemplate jdbc;

    public List<MailableFilm> forCreator(UUID tenantId) {
        return jdbc.query(SELECT + " AND s.tenant_id = ? ORDER BY s.sort_order, s.published_at DESC", ROW, tenantId);
    }

    public Optional<MailableFilm> byPublicId(UUID tenantId, String publicId) {
        return jdbc.query(SELECT + " AND s.tenant_id = ? AND s.public_id = ?", ROW, tenantId, publicId).stream().findFirst();
    }

    public Optional<MailableFilm> byItemId(UUID itemId) {
        return jdbc.query(SELECT + " AND s.id = ?", ROW, itemId).stream().findFirst();
    }

    /** Candidates for an automatic pick in an industry: creators who allow picks, films spotlighted
     * now first (rule 14), then by score. */
    public List<MailableFilm> forAutoPicks(ShowcaseIndustry industry, java.time.Instant now, int limit) {
        return jdbc.query(SELECT + " AND p.auto_picks_enabled = TRUE AND s.industry = ?"
                        + " ORDER BY (s.spotlight_until IS NOT NULL AND s.spotlight_until > ?) DESC,"
                        + " s.global_score DESC NULLS LAST, s.published_at DESC LIMIT ?",
                ROW, industry.name(), java.sql.Timestamp.from(now), limit);
    }

    /** Mailable films that went public since {@code since} (follower notices). */
    public List<MailableFilm> publishedSince(java.time.Instant since) {
        return jdbc.query(SELECT + " AND s.published_at > ? ORDER BY s.published_at", ROW, java.sql.Timestamp.from(since));
    }
}
