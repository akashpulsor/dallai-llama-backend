package com.dalai.llama.tenant.leadmanagement.creatormail;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ops_creator_mail_template}, {@code ops_creator_mail_send} (V58), {@code ops_creator_lead} and
 * {@code ops_creator_mail_delivery} (V60). */
@Repository
@RequiredArgsConstructor
public class CreatorMailStore {

    /** Who a send went to: every active creator, chosen creators, creators whose profile has no video
     * yet, the uploaded creator leads (not on the platform), or one address as a test. */
    public enum Audience { ALL, CHOSEN, NO_VIDEO, LEADS, BRANDS, TEST }

    /** Who a template is written for: creators (the creator audiences) or brands (the brand directory). */
    public enum MailFor { CREATORS, BRANDS }

    /** Which brands a brand mail goes to; an empty list doesn't narrow. */
    public record BrandFilter(List<com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry> industries, List<com.dalai.llama.tenant.leadmanagement.audience.CompanySize> sizes,
                              List<String> countries) {
    }

    /** How a template's mail looks: a plain personal letter (best for the main inbox), or the
     * designed platform mail with picture cards and a button (Gmail usually files it under Promotions). */
    public enum MailStyle { PLAIN, STYLED }

    /** {@code ctaLabel}/{@code ctaUrl}: the button the mail ends with; both null for none.
     * {@code ideaVideos}: how many inspiration-board videos the mail shows (0-3); {@code ideaIndustry}: which
     * industry they come from, null for a random one each mail. */
    public record Template(UUID id, String name, String subject, String body, String ctaLabel, String ctaUrl, int ideaVideos,
                           com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry ideaIndustry, MailStyle style, MailFor mailFor, boolean active,
                           OffsetDateTime lastSentAt) {
    }

    /** {@code queued}/{@code sent}: where its mails are now. */
    public record Send(UUID id, UUID templateId, String subject, Audience audience, int recipients, OffsetDateTime createdAt,
                       int queued, int sent) {
    }

    /** {@code company}/{@code industry}: the brand, for a brand mail; null for a creator. */
    public record Recipient(String email, String name, String recipientHash, String company, com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry industry) {
        public Recipient(String email, String name, String recipientHash) {
            this(email, name, recipientHash, null, null);
        }
    }

    public record QueuedMail(UUID id, UUID templateId, String email, String name, String recipientHash, String unsubscribeToken,
                             String company, com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry industry) {
    }

    public record Lead(String email, String name, String profileUrl) {
    }

    private static final String TEMPLATE_SELECT = "SELECT t.id, t.name, t.subject, t.body, t.active, t.cta_label, t.cta_url, t.idea_videos, t.style, t.idea_industry, t.mail_for,"
            + " (SELECT MAX(s.created_at) FROM ops_creator_mail_send s WHERE s.template_id = t.id AND s.audience <> 'TEST')"
            + " FROM ops_creator_mail_template t";
    private static final RowMapper<Template> TEMPLATE = (rs, n) -> new Template(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(6), rs.getString(7), rs.getInt(8),
            rs.getString(10) == null ? null : com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.valueOf(rs.getString(10)), MailStyle.valueOf(rs.getString(9)),
            MailFor.valueOf(rs.getString(11)), rs.getBoolean(5), rs.getObject(12, OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    // ---- templates ----

    public List<Template> templates(MailFor mailFor) {
        return jdbc.query(TEMPLATE_SELECT + " WHERE t.mail_for = ? ORDER BY t.active DESC, t.updated_at DESC", TEMPLATE, mailFor.name());
    }

    public Optional<Template> find(UUID id) {
        return jdbc.query(TEMPLATE_SELECT + " WHERE t.id = ?", TEMPLATE, id).stream().findFirst();
    }

    public Template insert(String name, String subject, String body, String ctaLabel, String ctaUrl, int ideaVideos,
                           com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry ideaIndustry, MailStyle style, MailFor mailFor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url, idea_videos, idea_industry, style,"
                + " mail_for) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, name, subject, body, ctaLabel, ctaUrl, ideaVideos, code(ideaIndustry),
                style.name(), mailFor.name());
        return find(id).orElseThrow();
    }

    public Optional<Template> update(UUID id, String name, String subject, String body, String ctaLabel, String ctaUrl, int ideaVideos,
                                     com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry ideaIndustry, MailStyle style, boolean active) {
        jdbc.update("UPDATE ops_creator_mail_template SET name = ?, subject = ?, body = ?, cta_label = ?, cta_url = ?, idea_videos = ?,"
                + " idea_industry = ?, style = ?, active = ?, updated_at = NOW() WHERE id = ?", name, subject, body, ctaLabel, ctaUrl,
                ideaVideos, code(ideaIndustry), style.name(), active, id);
        return find(id);
    }

    private static com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry industry(String code) {
        return code == null ? null : com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry.valueOf(code);
    }

    // ---- brands ----

    /** One contact per brand in the directory that matches: its best email (highest confidence). */
    public List<Recipient> brandContacts(BrandFilter filter) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT DISTINCT ON (c.id) LOWER(cp.value), cc.full_name, c.name, c.industry
                    FROM lead_company c
                    JOIN lead_company_contact cc ON cc.company_id = c.id
                    JOIN lead_contact_point cp ON cp.id = cc.contact_point_id AND cp.kind = 'EMAIL' AND cp.status <> 'INVALID'
                    WHERE (cardinality(?::varchar[]) = 0 OR c.industry = ANY (?::varchar[]))
                      AND (cardinality(?::varchar[]) = 0 OR c.size_band = ANY (?::varchar[]))
                      AND (cardinality(?::varchar[]) = 0 OR c.country_code = ANY (?::varchar[]))
                    ORDER BY c.id, cc.confidence DESC NULLS LAST, cp.value""");
            String[][] lists = {codes(filter.industries()), codes(filter.sizes()), filter.countries() == null ? new String[0]
                    : filter.countries().stream().map(c -> c.trim().toUpperCase(java.util.Locale.ROOT)).toArray(String[]::new)};
            for (int i = 0; i < 3; i++) {
                java.sql.Array array = con.createArrayOf("varchar", lists[i]);
                ps.setArray(2 * i + 1, array);
                ps.setArray(2 * i + 2, array);
            }
            return ps;
        }, (rs, n) -> new Recipient(rs.getString(1), rs.getString(2), null, rs.getString(3), industry(rs.getString(4))));
    }

    private static String[] codes(List<? extends Enum<?>> values) {
        return values == null ? new String[0] : values.stream().map(Enum::name).toArray(String[]::new);
    }

    /** The brand contact an address belongs to, for a test of a brand mail. */
    public Optional<Recipient> brandContact(String email) {
        return jdbc.query("""
                SELECT LOWER(cp.value), cc.full_name, c.name, c.industry FROM lead_company c
                JOIN lead_company_contact cc ON cc.company_id = c.id
                JOIN lead_contact_point cp ON cp.id = cc.contact_point_id AND cp.kind = 'EMAIL'
                WHERE LOWER(cp.value) = ? LIMIT 1""",
                (rs, n) -> new Recipient(rs.getString(1), rs.getString(2), null, rs.getString(3), industry(rs.getString(4))), email)
                .stream().findFirst();
    }

    private static String code(com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry industry) {
        return industry == null ? null : industry.name();
    }

    // ---- sends and the queue ----

    public UUID logSend(UUID templateId, String subject, Audience audience, int recipients) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ops_creator_mail_send (id, template_id, subject, audience, recipients) VALUES (?, ?, ?, ?, ?)",
                id, templateId, subject, audience.name(), recipients);
        return id;
    }

    public void setRecipients(UUID sendId, int recipients) {
        jdbc.update("UPDATE ops_creator_mail_send SET recipients = ? WHERE id = ?", recipients, sendId);
    }

    /** Queues one mail; false when this template already reached (or is queued for) the address. */
    public boolean enqueue(UUID sendId, UUID templateId, Recipient to) {
        return jdbc.update("""
                INSERT INTO ops_creator_mail_delivery (id, send_id, template_id, email, name, recipient_hash, unsubscribe_token, company, industry)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (template_id, email) WHERE status <> 'TEST' DO NOTHING""",
                UUID.randomUUID(), sendId, templateId, to.email(), to.name(), to.recipientHash(), newToken(), to.company(),
                code(to.industry())) == 1;
    }

    /** Records a test mail (sent straight away) and returns its unsubscribe token, so the link in a
     * test works like a real one. */
    public String recordTest(UUID sendId, UUID templateId, Recipient to) {
        String token = newToken();
        jdbc.update("""
                INSERT INTO ops_creator_mail_delivery (id, send_id, template_id, email, name, recipient_hash, unsubscribe_token, status, sent_at,
                    company, industry)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST', NOW(), ?, ?)""",
                UUID.randomUUID(), sendId, templateId, to.email(), to.name(), to.recipientHash(), token, to.company(), code(to.industry()));
        return token;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public List<QueuedMail> nextQueued(int limit) {
        return jdbc.query("""
                SELECT id, template_id, email, name, recipient_hash, unsubscribe_token, company, industry FROM ops_creator_mail_delivery
                WHERE status = 'QUEUED' ORDER BY created_at, id LIMIT ?""",
                (rs, n) -> new QueuedMail(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), industry(rs.getString(8))), limit);
    }

    /** {@code at}: when it went (the service's clock, the same one the day's cap counts by). */
    public void mark(UUID deliveryId, String status, java.time.Instant at) {
        jdbc.update("UPDATE ops_creator_mail_delivery SET status = ?, sent_at = CASE WHEN ? = 'SENT' THEN ? ELSE sent_at END WHERE id = ?",
                status, status, java.sql.Timestamp.from(at), deliveryId);
    }

    public int queuedCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ops_creator_mail_delivery WHERE status = 'QUEUED'", Integer.class);
        return n == null ? 0 : n;
    }

    public int sentOn(LocalDate day) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ops_creator_mail_delivery WHERE status = 'SENT' AND sent_at >= ? AND sent_at < ?",
                Integer.class, day.atStartOfDay().atOffset(ZoneOffset.UTC), day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        return n == null ? 0 : n;
    }

    public List<Send> recentSends(int limit) {
        return jdbc.query("""
                SELECT s.id, s.template_id, s.subject, s.audience, s.recipients, s.created_at,
                       COUNT(d.id) FILTER (WHERE d.status = 'QUEUED'), COUNT(d.id) FILTER (WHERE d.status = 'SENT')
                FROM ops_creator_mail_send s LEFT JOIN ops_creator_mail_delivery d ON d.send_id = s.id
                GROUP BY s.id ORDER BY s.created_at DESC LIMIT ?""",
                (rs, n) -> new Send(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), Audience.valueOf(rs.getString(4)),
                        rs.getInt(5), rs.getObject(6, OffsetDateTime.class), rs.getInt(7), rs.getInt(8)), limit);
    }

    // ---- creator leads ----

    /** True when the address is new. Known leads keep their details; blanks are filled. */
    public boolean upsertLead(Lead lead) {
        return jdbc.queryForObject("""
                INSERT INTO ops_creator_lead (id, email, name, profile_url) VALUES (?, ?, ?, ?)
                ON CONFLICT (email) DO UPDATE SET name = COALESCE(ops_creator_lead.name, EXCLUDED.name),
                    profile_url = COALESCE(ops_creator_lead.profile_url, EXCLUDED.profile_url)
                RETURNING (xmax = 0)""", Boolean.class, UUID.randomUUID(), lead.email(), lead.name(), lead.profileUrl());
    }

    /** Leads whose address isn't a creator account on the platform already. */
    public List<Lead> leadsNotOnPlatform() {
        return jdbc.query("""
                SELECT l.email, l.name, l.profile_url FROM ops_creator_lead l
                WHERE NOT EXISTS (SELECT 1 FROM tenants t WHERE LOWER(t.primary_contact_email) = l.email)
                ORDER BY l.created_at""", (rs, n) -> new Lead(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /** The names we know an address by, best first: an active creator account's contact name, then any
     * other account's, then its creator lead's (one address can have several accounts). */
    public List<String> namesOf(String email) {
        return jdbc.queryForList("""
                SELECT name FROM (
                    SELECT t.primary_contact_name AS name,
                           CASE WHEN t.account_type = 'CREATOR' AND t.status = 'ACTIVE' THEN 0 ELSE 1 END AS rank, t.created_at
                    FROM tenants t WHERE LOWER(t.primary_contact_email) = ?
                    UNION ALL
                    SELECT l.name, 2, l.created_at FROM ops_creator_lead l WHERE l.email = ?
                ) known WHERE name IS NOT NULL AND name <> '' ORDER BY rank, created_at""", String.class, email, email);
    }

    public int leadCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ops_creator_lead", Integer.class);
        return n == null ? 0 : n;
    }
}
