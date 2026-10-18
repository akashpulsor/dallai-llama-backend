package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only films any outreach may carry (rule 9): live platform films the client fully paid for
 * and agreed to marketing use of, on an active profile, not hidden by ops, still playable. */
@Component
@RequiredArgsConstructor
public class MailableFilms {

    /** What a mailer shows of a film: the same facts its card on the site shows. */
    public record MailableFilm(UUID itemId, String publicId, String title, String thumbnailUrl, ShowcaseIndustry industry,
                               String clientLabel, UUID tenantId, String handle, String creatorName, ShowcaseFormat format,
                               BigDecimal durationSeconds, boolean vertical) {
        public MailableFilm(UUID itemId, String publicId, String title, String thumbnailUrl, ShowcaseIndustry industry,
                            String clientLabel, UUID tenantId, String handle, String creatorName) {
            this(itemId, publicId, title, thumbnailUrl, industry, clientLabel, tenantId, handle, creatorName, null, null, false);
        }
    }

    private static final String SELECT = """
            SELECT s.id, s.public_id, COALESCE(s.title_override, v.title, 'Film') AS title, s.industry,
                   s.client_label, s.tenant_id, p.handle, p.display_name, s.format,
                   COALESCE(v.duration_seconds, s.film_duration_seconds) AS duration,
                   COALESCE(v.aspect_w, s.film_width) AS w, COALESCE(v.aspect_h, s.film_height) AS h
            FROM showcase_item s
            JOIN creator_public_profile p ON p.tenant_id = s.tenant_id
            LEFT JOIN youtube_video v ON v.video_id = s.youtube_video_id
            WHERE s.status = 'LIVE' AND s.origin = 'PLATFORM' AND s.hidden_by_ops = FALSE
              AND s.funded_verified_at IS NOT NULL AND s.marketing_consent_at IS NOT NULL
              AND p.status = 'ACTIVE'
              AND (s.host = 'SELF' OR (v.video_id IS NOT NULL AND v.gone_at IS NULL))
            """;

    private final JdbcTemplate jdbc;

    /** Every mailable film is made here, so it shows its own poster (the frame the site shows),
     * never YouTube's thumbnail, which a film not on YouTube doesn't have. */
    @Value("${showcase.public-api-base-url:https://api.dalaillama.in/api/v1/public}")
    private String publicApiBaseUrl;

    private final RowMapper<MailableFilm> row = (rs, n) -> {
        int w = rs.getInt(11), h = rs.getInt(12);
        return new MailableFilm(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                publicApiBaseUrl + "/showcase/" + rs.getString(2) + "/poster", ShowcaseIndustry.valueOf(rs.getString(4)),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), ShowcaseFormat.valueOf(rs.getString(9)),
                rs.getBigDecimal(10), h > w);
    };

    public List<MailableFilm> forCreator(UUID tenantId) {
        return jdbc.query(SELECT + " AND s.tenant_id = ? ORDER BY s.sort_order, s.published_at DESC", row, tenantId);
    }

    public Optional<MailableFilm> byPublicId(UUID tenantId, String publicId) {
        return jdbc.query(SELECT + " AND s.tenant_id = ? AND s.public_id = ?", row, tenantId, publicId).stream().findFirst();
    }

    public Optional<MailableFilm> byItemId(UUID itemId) {
        return jdbc.query(SELECT + " AND s.id = ?", row, itemId).stream().findFirst();
    }

    /** Candidates for an automatic pick in an industry: creators who allow picks, films spotlighted
     * now first (rule 14), then by score. */
    public List<MailableFilm> forAutoPicks(ShowcaseIndustry industry, java.time.Instant now, int limit) {
        return jdbc.query(SELECT + " AND p.auto_picks_enabled = TRUE AND s.industry = ?"
                        + " ORDER BY (s.spotlight_until IS NOT NULL AND s.spotlight_until > ?) DESC,"
                        + " s.global_score DESC NULLS LAST, s.published_at DESC LIMIT ?",
                row, industry.name(), java.sql.Timestamp.from(now), limit);
    }

    /** Mailable films that went public since {@code since} (follower notices). */
    public List<MailableFilm> publishedSince(java.time.Instant since) {
        return jdbc.query(SELECT + " AND s.published_at > ? ORDER BY s.published_at", row, java.sql.Timestamp.from(since));
    }
}
