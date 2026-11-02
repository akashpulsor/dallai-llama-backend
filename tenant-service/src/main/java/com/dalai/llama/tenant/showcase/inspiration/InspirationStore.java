package com.dalai.llama.tenant.showcase.inspiration;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class InspirationStore {

    private static final RowMapper<InspirationItem> ROW = (rs, n) -> new InspirationItem(
            rs.getObject("id", UUID.class), rs.getString("pin_id"), InspirationPins.url(rs.getString("pin_id")),
            rs.getString("title"), ShowcaseIndustry.valueOf(rs.getString("industry")), rs.getString("note"),
            rs.getObject("embed_height", Integer.class), rs.getString("poster_url"), rs.getObject("poster_width", Integer.class),
            rs.getObject("poster_height", Integer.class), rs.getString("video_url"), rs.getBigDecimal("duration_seconds"),
            rs.getString("maker_name"));

    private final JdbcTemplate jdbc;

    /** Active items, one industry or all, in ops' order. */
    public List<InspirationItem> active(ShowcaseIndustry industry) {
        return industry == null
                ? jdbc.query("SELECT * FROM inspiration_item WHERE active ORDER BY industry, sort_order, created_at", ROW)
                : jdbc.query("SELECT * FROM inspiration_item WHERE active AND industry = ? ORDER BY sort_order, created_at",
                        ROW, industry.name());
    }

    /** Adds the pin, or updates its labels and shows it again when it is already on the board. A
     * missing {@code embedHeight} keeps the one already known. */
    public InspirationItem save(String pinId, String title, ShowcaseIndustry industry, String note, int sortOrder, Integer embedHeight) {
        jdbc.update("""
                INSERT INTO inspiration_item (id, pin_id, title, industry, note, sort_order, embed_height) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (pin_id) DO UPDATE SET title = EXCLUDED.title, industry = EXCLUDED.industry,
                    note = EXCLUDED.note, sort_order = EXCLUDED.sort_order, active = TRUE,
                    embed_height = COALESCE(EXCLUDED.embed_height, inspiration_item.embed_height)""",
                UUID.randomUUID(), pinId, title, industry.name(), note, sortOrder, embedHeight);
        return jdbc.queryForObject("SELECT * FROM inspiration_item WHERE pin_id = ?", ROW, pinId);
    }

    /** An inspiration video as mail shows it: its picture (null until fetched) and who made it. */
    public record MailIdea(UUID id, String pinId, String pinUrl, String title, ShowcaseIndustry industry, String posterUrl,
                           Integer posterWidth, Integer posterHeight, String makerName) {
    }

    private static final RowMapper<MailIdea> MAIL_ROW = (rs, n) -> new MailIdea(
            rs.getObject("id", UUID.class), rs.getString("pin_id"), InspirationPins.url(rs.getString("pin_id")),
            rs.getString("title"), ShowcaseIndustry.valueOf(rs.getString("industry")), rs.getString("poster_url"),
            rs.getObject("poster_width", Integer.class), rs.getObject("poster_height", Integer.class), rs.getString("maker_name"));

    /** Active items for mail, those of the given industries first (in ops' order), then the rest. */
    public List<MailIdea> forMail(List<ShowcaseIndustry> industries, int limit) {
        String[] codes = industries.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT * FROM inspiration_item WHERE active
                    ORDER BY CASE WHEN industry = ANY (?) THEN 0 ELSE 1 END, sort_order, created_at LIMIT ?""");
            ps.setArray(1, con.createArrayOf("varchar", codes));
            ps.setInt(2, limit);
            return ps;
        }, MAIL_ROW);
    }

    /** Random active items that have a picture, all of one industry: the given one, or one picked at random
     * among the industries that have any. */
    public List<MailIdea> randomForMail(ShowcaseIndustry industry, int limit) {
        return jdbc.query("""
                SELECT * FROM inspiration_item WHERE active AND poster_url IS NOT NULL
                  AND industry = COALESCE(?, (SELECT industry FROM inspiration_item WHERE active AND poster_url IS NOT NULL
                                              GROUP BY industry ORDER BY random() LIMIT 1))
                ORDER BY random() LIMIT ?""", MAIL_ROW, industry == null ? null : industry.name(), limit);
    }

    /** A video for mail, and when the address was last sent it (null: never). */
    public record SeenIdea(MailIdea idea, java.time.Instant lastSent) {
    }

    /** Every active video that has a picture, each with when {@code email} was last sent it. */
    public List<SeenIdea> forMailTo(String email) {
        return jdbc.query("""
                SELECT i.*, s.sent_at AS last_sent FROM inspiration_item i
                LEFT JOIN ops_mail_idea_sent s ON s.item_id = i.id AND s.email = ?
                WHERE i.active AND i.poster_url IS NOT NULL""",
                (rs, n) -> new SeenIdea(MAIL_ROW.mapRow(rs, n), rs.getTimestamp("last_sent") == null ? null : rs.getTimestamp("last_sent").toInstant()),
                email == null ? "" : email);
    }

    /** Remembers that {@code email} was sent these videos. */
    public void recordSent(String email, List<UUID> itemIds, java.time.Instant at) {
        for (UUID item : itemIds) {
            jdbc.update("""
                    INSERT INTO ops_mail_idea_sent (email, item_id, sent_at) VALUES (?, ?, ?)
                    ON CONFLICT (email, item_id) DO UPDATE SET sent_at = EXCLUDED.sent_at""", email, item, java.sql.Timestamp.from(at));
        }
    }

    public void setPoster(UUID id, InspirationPosters.Poster poster) {
        jdbc.update("""
                UPDATE inspiration_item SET poster_url = ?, poster_width = ?, poster_height = ?, maker_name = ?, video_url = ?,
                    duration_seconds = ? WHERE id = ?""",
                poster.url(), poster.width(), poster.height(), poster.makerName(), poster.videoUrl(), poster.durationSeconds(), id);
    }

    /** Active items whose picture or video isn't known yet. */
    public List<InspirationItem> withoutMedia(int limit) {
        return jdbc.query("SELECT * FROM inspiration_item WHERE active AND (poster_url IS NULL OR video_url IS NULL) "
                + "ORDER BY created_at LIMIT ?", ROW, limit);
    }

    public boolean hide(UUID id) {
        return jdbc.update("UPDATE inspiration_item SET active = FALSE WHERE id = ?", id) == 1;
    }
}
