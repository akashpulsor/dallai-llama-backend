package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.showcase.inspiration.InspirationStore.MailIdea;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What each ops mail showed and what its recipient did with it (V75): the videos in it, its tracked
 * links and their clicks, when it was opened, and the requests a brand sent after it. A request counts
 * for a mail when the brand (by its address) sent it after that mail went. */
@Repository
@RequiredArgsConstructor
public class OpsMailHistoryStore {

    /** {@code onBoard}: false once the video was taken off the inspiration board (the mail still showed it). */
    public record ShownVideo(String title, String posterUrl, boolean onBoard) {
    }

    public record MailLink(String label, int clicks, OffsetDateTime firstClickedAt) {
    }

    /** One mail a brand got. {@code subject} as the template had it then ({name} not filled in);
     * {@code videosFit}: see V75, null when there was nothing to match. */
    public record SentMail(UUID deliveryId, OffsetDateTime sentAt, String templateName, String subject, OffsetDateTime openedAt,
                           OffsetDateTime firstClickedAt, Boolean videosFit) {
    }

    public record BrandRequest(UUID inquiryId, OffsetDateTime createdAt, OffsetDateTime paidAt, String status) {
    }

    /** A brand that was mailed: how many mails, how many it opened or clicked, requests since the first. */
    public record BrandMailSummary(String email, String company, int mails, OffsetDateTime lastSentAt, int opened, int clicked, int requests) {
    }

    /** {@code requested}: mails whose brand sent a request after it. */
    public record TemplateStats(UUID templateId, String name, boolean active, int sent, int opened, int clicked, int requested) {
    }

    /** A brand mail that went with no video about what the brand sells. */
    public record Shortfall(String company, String topics) {
    }

    private final JdbcTemplate jdbc;

    // ---- recording ----

    public void recordSent(UUID deliveryId, List<MailIdea> ideas, List<TrackedLinks.Link> links, Boolean videosFit) {
        for (int i = 0; i < ideas.size(); i++) {
            MailIdea idea = ideas.get(i);
            jdbc.update("INSERT INTO ops_mail_delivery_video (delivery_id, position, item_id, title, poster_url) VALUES (?, ?, ?, ?, ?)",
                    deliveryId, i, idea.id(), truncate(idea.title(), 200), truncate(idea.posterUrl(), 500));
        }
        for (TrackedLinks.Link link : links) {
            jdbc.update("INSERT INTO ops_mail_delivery_link (delivery_id, position, label, url) VALUES (?, ?, ?, ?)",
                    deliveryId, link.position(), link.label(), truncate(link.url(), 1000));
        }
        jdbc.update("UPDATE ops_creator_mail_delivery SET videos_fit = ? WHERE id = ?", videosFit, deliveryId);
    }

    /** Counts a click; the link's address, or empty when it isn't one of ours. A click is an open too. */
    public Optional<String> click(UUID deliveryId, int position, Instant at) {
        Timestamp when = Timestamp.from(at);
        Optional<String> url = jdbc.queryForList("""
                UPDATE ops_mail_delivery_link SET clicks = clicks + 1, first_clicked_at = COALESCE(first_clicked_at, ?)
                WHERE delivery_id = ? AND position = ? RETURNING url""", String.class, when, deliveryId, position).stream().findFirst();
        url.ifPresent(u -> jdbc.update("""
                UPDATE ops_creator_mail_delivery SET first_clicked_at = COALESCE(first_clicked_at, ?), opened_at = COALESCE(opened_at, ?)
                WHERE id = ?""", when, when, deliveryId));
        return url;
    }

    /** An open of an ops mail, for analytics: who it was for (CREATORS/BRANDS), its recipient (hash, or the
     * delivery id when it has none), the template's name, and whether it's the first open. */
    public record Opened(String mailFor, String recipientKey, String templateName, boolean firstOpen) {
    }

    /** Records an open; empty when the mail isn't one of ours. */
    public Optional<Opened> open(UUID deliveryId, Instant at) {
        Timestamp when = Timestamp.from(at);
        return jdbc.query("""
                WITH before AS (SELECT opened_at FROM ops_creator_mail_delivery WHERE id = ?)
                UPDATE ops_creator_mail_delivery d SET opened_at = COALESCE(d.opened_at, ?)
                FROM ops_creator_mail_template t, before
                WHERE d.id = ? AND t.id = d.template_id
                RETURNING t.mail_for, COALESCE(d.recipient_hash, d.id::text), t.name, before.opened_at IS NULL""",
                (rs, n) -> new Opened(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                deliveryId, when, deliveryId).stream().findFirst();
    }

    // ---- reading ----

    /** Brands that were sent brand mail, latest first; {@code query} narrows by address or name. */
    public List<BrandMailSummary> brands(String query, int limit) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        return jdbc.query("""
                SELECT d.email, MAX(d.company), COUNT(*), MAX(d.sent_at), COUNT(d.opened_at), COUNT(d.first_clicked_at),
                       (SELECT COUNT(*) FROM lead_brand_inquiry i JOIN lead_brand_contact c ON c.id = i.brand_contact_id
                        WHERE LOWER(c.email) = d.email AND i.created_at >= MIN(d.sent_at))
                FROM ops_creator_mail_delivery d JOIN ops_creator_mail_template t ON t.id = d.template_id
                WHERE t.mail_for = 'BRANDS' AND d.status = 'SENT' AND (d.email ILIKE ? OR d.company ILIKE ?)
                GROUP BY d.email ORDER BY MAX(d.sent_at) DESC LIMIT ?""",
                (rs, n) -> new BrandMailSummary(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4, OffsetDateTime.class),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7)), like, like, limit);
    }

    /** Every mail an address was sent, latest first. */
    public List<SentMail> mailsTo(String email) {
        return jdbc.query("""
                SELECT d.id, d.sent_at, t.name, s.subject, d.opened_at, d.first_clicked_at, d.videos_fit
                FROM ops_creator_mail_delivery d JOIN ops_creator_mail_template t ON t.id = d.template_id
                JOIN ops_creator_mail_send s ON s.id = d.send_id
                WHERE d.email = ? AND d.status = 'SENT' ORDER BY d.sent_at DESC""",
                (rs, n) -> new SentMail(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), (Boolean) rs.getObject(7)), email);
    }

    public List<ShownVideo> videosOf(UUID deliveryId) {
        return jdbc.query("SELECT title, poster_url, item_id IS NOT NULL FROM ops_mail_delivery_video WHERE delivery_id = ? ORDER BY position",
                (rs, n) -> new ShownVideo(rs.getString(1), rs.getString(2), rs.getBoolean(3)), deliveryId);
    }

    public List<MailLink> linksOf(UUID deliveryId) {
        return jdbc.query("SELECT label, clicks, first_clicked_at FROM ops_mail_delivery_link WHERE delivery_id = ? ORDER BY position",
                (rs, n) -> new MailLink(rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime.class)), deliveryId);
    }

    /** The requests a brand (by its address) has sent, latest first. */
    public List<BrandRequest> requestsBy(String email) {
        return jdbc.query("""
                SELECT i.id, i.created_at, i.paid_at, i.status FROM lead_brand_inquiry i JOIN lead_brand_contact c ON c.id = i.brand_contact_id
                WHERE LOWER(c.email) = ? ORDER BY i.created_at DESC""",
                (rs, n) -> new BrandRequest(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, OffsetDateTime.class), rs.getString(4)), email);
    }

    /** Each brand template that has gone out, with how its mails did. */
    public List<TemplateStats> templateStats() {
        return jdbc.query("""
                SELECT t.id, t.name, t.active, COUNT(d.id), COUNT(d.opened_at), COUNT(d.first_clicked_at),
                       COUNT(d.id) FILTER (WHERE EXISTS (
                           SELECT 1 FROM lead_brand_inquiry i JOIN lead_brand_contact c ON c.id = i.brand_contact_id
                           WHERE LOWER(c.email) = d.email AND i.created_at >= d.sent_at))
                FROM ops_creator_mail_template t JOIN ops_creator_mail_delivery d ON d.template_id = t.id AND d.status = 'SENT'
                WHERE t.mail_for = 'BRANDS' GROUP BY t.id, t.name, t.active ORDER BY MAX(d.sent_at) DESC""",
                (rs, n) -> new TemplateStats(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7)));
    }

    public List<Shortfall> shortfallsSince(Instant since) {
        return jdbc.query("SELECT company, topics FROM ops_creator_mail_delivery WHERE videos_fit = FALSE AND sent_at >= ? ORDER BY sent_at",
                (rs, n) -> new Shortfall(rs.getString(1), rs.getString(2)), Timestamp.from(since));
    }

    // ---- coverage ----

    /** What each brand in the directory is known by (its category, name and website), as brand mail matches on. */
    public List<String> brandTopics() {
        return jdbc.queryForList("SELECT concat_ws(' ', category, name, domain) FROM lead_company", String.class);
    }

    /** The titles of the inspiration videos mail can show (active, with a picture). */
    public List<String> mailableVideoTitles() {
        return jdbc.queryForList("SELECT title FROM inspiration_item WHERE active AND poster_url IS NOT NULL", String.class);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
