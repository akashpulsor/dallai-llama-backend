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
    public enum Audience { ALL, CHOSEN, NO_VIDEO, LEADS, TEST }

    /** {@code ctaLabel}/{@code ctaUrl}: the button the mail ends with; both null for none. */
    public record Template(UUID id, String name, String subject, String body, String ctaLabel, String ctaUrl, boolean active,
                           OffsetDateTime lastSentAt) {
    }

    /** {@code queued}/{@code sent}: where its mails are now. */
    public record Send(UUID id, UUID templateId, String subject, Audience audience, int recipients, OffsetDateTime createdAt,
                       int queued, int sent) {
    }

    public record Recipient(String email, String name, String recipientHash) {
    }

    public record QueuedMail(UUID id, UUID templateId, String email, String name, String recipientHash, String unsubscribeToken) {
    }

    public record Lead(String email, String name, String profileUrl) {
    }

    private static final String TEMPLATE_SELECT = "SELECT t.id, t.name, t.subject, t.body, t.active, t.cta_label, t.cta_url,"
            + " (SELECT MAX(s.created_at) FROM ops_creator_mail_send s WHERE s.template_id = t.id AND s.audience <> 'TEST')"
            + " FROM ops_creator_mail_template t";
    private static final RowMapper<Template> TEMPLATE = (rs, n) -> new Template(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(6), rs.getString(7), rs.getBoolean(5), rs.getObject(8, OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    // ---- templates ----

    public List<Template> templates() {
        return jdbc.query(TEMPLATE_SELECT + " ORDER BY t.active DESC, t.updated_at DESC", TEMPLATE);
    }

    public Optional<Template> find(UUID id) {
        return jdbc.query(TEMPLATE_SELECT + " WHERE t.id = ?", TEMPLATE, id).stream().findFirst();
    }

    public Template insert(String name, String subject, String body, String ctaLabel, String ctaUrl) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ops_creator_mail_template (id, name, subject, body, cta_label, cta_url) VALUES (?, ?, ?, ?, ?, ?)",
                id, name, subject, body, ctaLabel, ctaUrl);
        return find(id).orElseThrow();
    }

    public Optional<Template> update(UUID id, String name, String subject, String body, String ctaLabel, String ctaUrl, boolean active) {
        jdbc.update("UPDATE ops_creator_mail_template SET name = ?, subject = ?, body = ?, cta_label = ?, cta_url = ?, active = ?,"
                + " updated_at = NOW() WHERE id = ?", name, subject, body, ctaLabel, ctaUrl, active, id);
        return find(id);
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
                INSERT INTO ops_creator_mail_delivery (id, send_id, template_id, email, name, recipient_hash, unsubscribe_token)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (template_id, email) DO NOTHING""",
                UUID.randomUUID(), sendId, templateId, to.email(), to.name(), to.recipientHash(),
                UUID.randomUUID().toString().replace("-", "")) == 1;
    }

    public List<QueuedMail> nextQueued(int limit) {
        return jdbc.query("""
                SELECT id, template_id, email, name, recipient_hash, unsubscribe_token FROM ops_creator_mail_delivery
                WHERE status = 'QUEUED' ORDER BY created_at, id LIMIT ?""",
                (rs, n) -> new QueuedMail(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), limit);
    }

    public void mark(UUID deliveryId, String status) {
        jdbc.update("UPDATE ops_creator_mail_delivery SET status = ?, sent_at = CASE WHEN ? = 'SENT' THEN NOW() ELSE sent_at END WHERE id = ?",
                status, status, deliveryId);
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

    public int leadCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ops_creator_lead", Integer.class);
        return n == null ? 0 : n;
    }
}
