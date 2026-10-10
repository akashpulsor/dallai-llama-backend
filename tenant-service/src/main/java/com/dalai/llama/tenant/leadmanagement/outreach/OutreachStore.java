package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.common.token.PublicTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outreach tables (V33). The one-delivery-a-day rule lives in the database: {@link #claimDay}
 * either takes today's slot for a recipient or reports it's gone. */
@Repository
@RequiredArgsConstructor
public class OutreachStore {

    /** QUEUED: waiting for the dispatcher. PENDING: waiting for the recipient's next digest. */
    public enum IntentStatus { QUEUED, PENDING, SENT, DIGESTED, EXPIRED, FAILED }

    public enum Origin { CREATOR, AUTO_PICK, FOLLOW }

    public record NewIntent(UUID tenantId, String recipientHash, String email, String name, String company,
                            OutreachTemplate template, Origin origin, UUID showcaseItemId, String note, UUID mailPackId,
                            UUID emailTemplateId, UUID audienceId, UUID leadId) {
    }

    /** Everything the dispatcher needs to send one queued intent. */
    public record QueuedIntent(UUID id, UUID tenantId, String recipientHash, String email, String name, OutreachTemplate template,
                               UUID showcaseItemId, String note, UUID mailPackId, UUID emailTemplateId) {
    }

    public record PendingIntent(UUID id, UUID tenantId, String email, String name, OutreachTemplate template, Origin origin,
                                UUID showcaseItemId, Instant createdAt) {
    }

    public record IntentRow(UUID id, String email, String company, OutreachTemplate template, Origin origin,
                            IntentStatus status, String filmPublicId, Instant createdAt) {
    }

    public record Delivery(UUID id, String unsubscribeToken) {
    }

    private final JdbcTemplate jdbc;

    // ---- suppression ----

    public boolean isSuppressed(String recipientHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM lead_suppression WHERE recipient_hash = ?)", Boolean.class, recipientHash));
    }

    public void suppress(String recipientHash, String reason) {
        jdbc.update("INSERT INTO lead_suppression (recipient_hash, reason) VALUES (?, ?) ON CONFLICT DO NOTHING",
                recipientHash, reason);
        // Nothing queued for them goes out any more.
        jdbc.update("""
                UPDATE lead_outreach_intent SET status = 'EXPIRED', decided_at = NOW()
                WHERE recipient_hash = ? AND status IN ('QUEUED', 'PENDING')""", recipientHash);
    }

    public Optional<String> recipientOfUnsubscribeToken(String token) {
        return jdbc.queryForList("SELECT recipient_hash FROM lead_outreach_delivery WHERE unsubscribe_token = ?", String.class, token)
                .stream().findFirst();
    }

    // ---- intents ----

    public boolean contactedWithin(UUID tenantId, String recipientHash, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM lead_outreach_intent
                               WHERE tenant_id = ? AND recipient_hash = ? AND created_at > ? AND status <> 'FAILED')""",
                Boolean.class, tenantId, recipientHash, Timestamp.from(since)));
    }

    public UUID addIntent(NewIntent i, IntentStatus status, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lead_outreach_intent (id, tenant_id, recipient_hash, recipient_email, recipient_name, company_name,
                    template, origin, showcase_item_id, personal_note, status, mail_pack_id, email_template_id, audience_id,
                    lead_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, i.tenantId(), i.recipientHash(), i.email(), i.name(), i.company(), i.template().name(), i.origin().name(),
                i.showcaseItemId(), i.note(), status.name(), i.mailPackId(), i.emailTemplateId(), i.audienceId(), i.leadId(),
                Timestamp.from(now));
        return id;
    }

    /** Oldest queued intents first; the dispatcher locks each one before sending it. */
    public List<UUID> queuedIds(int limit) {
        return jdbc.queryForList("SELECT id FROM lead_outreach_intent WHERE status = 'QUEUED' ORDER BY created_at LIMIT ?",
                UUID.class, limit);
    }

    /** Locks one queued intent; empty when another run took it or it is no longer queued. */
    public Optional<QueuedIntent> lockQueued(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, recipient_hash, recipient_email, recipient_name, template, showcase_item_id, personal_note,
                       mail_pack_id, email_template_id
                FROM lead_outreach_intent WHERE id = ? AND status = 'QUEUED' FOR UPDATE SKIP LOCKED""",
                (rs, n) -> new QueuedIntent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), OutreachTemplate.valueOf(rs.getString(6)), rs.getObject(7, UUID.class),
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class)),
                id).stream().findFirst();
    }

    /** A mail that will never go out gives its pack mail back (free mails come back on their own:
     * FAILED intents don't count against the week). */
    public void refundPack(UUID packId) {
        if (packId != null) jdbc.update("UPDATE lead_mail_pack SET remaining = remaining + 1 WHERE id = ? AND remaining < quantity", packId);
    }

    public void markIntents(List<UUID> ids, IntentStatus status, UUID deliveryId, Instant now) {
        for (UUID id : ids) {
            jdbc.update("UPDATE lead_outreach_intent SET status = ?, delivery_id = ?, decided_at = ? WHERE id = ?",
                    status.name(), deliveryId, Timestamp.from(now), id);
        }
    }

    public List<String> recipientsWithPending() {
        return jdbc.queryForList("SELECT DISTINCT recipient_hash FROM lead_outreach_intent WHERE status = 'PENDING'", String.class);
    }

    public List<PendingIntent> pendingFor(String recipientHash) {
        return jdbc.query("""
                SELECT id, tenant_id, recipient_email, recipient_name, template, origin, showcase_item_id, created_at
                FROM lead_outreach_intent WHERE recipient_hash = ? AND status = 'PENDING' ORDER BY created_at""",
                (rs, n) -> new PendingIntent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), OutreachTemplate.valueOf(rs.getString(5)), Origin.valueOf(rs.getString(6)),
                        rs.getObject(7, UUID.class), rs.getTimestamp(8).toInstant()),
                recipientHash);
    }

    public int expirePendingBefore(Instant cutoff) {
        return jdbc.update("UPDATE lead_outreach_intent SET status = 'EXPIRED', decided_at = NOW() WHERE status = 'PENDING' AND created_at < ?",
                Timestamp.from(cutoff));
    }

    public List<IntentRow> recentForCreator(UUID tenantId, Instant since) {
        return jdbc.query("""
                SELECT i.id, i.recipient_email, i.company_name, i.template, i.origin, i.status, s.public_id, i.created_at
                FROM lead_outreach_intent i LEFT JOIN showcase_item s ON s.id = i.showcase_item_id
                WHERE i.tenant_id = ? AND i.created_at > ? ORDER BY i.created_at DESC LIMIT 200""",
                (rs, n) -> new IntentRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        OutreachTemplate.valueOf(rs.getString(4)), Origin.valueOf(rs.getString(5)),
                        IntentStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getTimestamp(8).toInstant()),
                tenantId, Timestamp.from(since));
    }

    /** Films this recipient was already sent or has queued, so automatic picks rotate. */
    public java.util.Set<UUID> itemsFor(String recipientHash) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT showcase_item_id FROM lead_outreach_intent
                WHERE recipient_hash = ? AND showcase_item_id IS NOT NULL AND status <> 'FAILED'""", UUID.class, recipientHash));
    }

    public record Reach(int mailsDelivered, int clicks, int requestsFromMail) {
    }

    /** What a creator's outreach did since {@code since}. */
    public Reach reach(UUID tenantId, Instant since) {
        Timestamp at = Timestamp.from(since);
        Integer delivered = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lead_outreach_intent WHERE tenant_id = ? AND status IN ('SENT', 'DIGESTED') AND created_at > ?""",
                Integer.class, tenantId, at);
        Integer clicks = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.click_count), 0) FROM lead_outreach_link l
                JOIN lead_outreach_delivery d ON d.id = l.delivery_id
                WHERE l.tenant_id = ? AND d.delivery_day >= ?""", Integer.class, tenantId,
                Date.valueOf(since.atZone(java.time.ZoneOffset.UTC).toLocalDate()));
        Integer requests = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lead_brand_inquiry WHERE tenant_id = ? AND attribution_token IS NOT NULL AND created_at > ?""",
                Integer.class, tenantId, at);
        return new Reach(delivered == null ? 0 : delivered, clicks == null ? 0 : clicks, requests == null ? 0 : requests);
    }

    // ---- deliveries and links ----

    /** Takes {@code day}'s single slot for this recipient; empty if something already went today. */
    public Optional<Delivery> claimDay(String recipientHash, LocalDate day, String kind, UUID tenantId, String email, String subject) {
        UUID id = UUID.randomUUID();
        String unsubscribe = PublicTokens.newToken();
        int inserted = jdbc.update("""
                INSERT INTO lead_outreach_delivery (id, recipient_hash, delivery_day, kind, tenant_id, recipient_email, subject,
                    unsubscribe_token, sent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                ON CONFLICT (recipient_hash, delivery_day) DO NOTHING""",
                id, recipientHash, Date.valueOf(day), kind, tenantId, email, subject, unsubscribe);
        return inserted == 1 ? Optional.of(new Delivery(id, unsubscribe)) : Optional.empty();
    }

    public void markSent(UUID deliveryId, String subject) {
        jdbc.update("UPDATE lead_outreach_delivery SET sent = TRUE, subject = ? WHERE id = ?", subject, deliveryId);
    }

    /** A send that failed gives the day back, so the digest can try again. */
    public void releaseDay(UUID deliveryId) {
        jdbc.update("DELETE FROM lead_outreach_delivery WHERE id = ?", deliveryId);
    }

    public void addLink(String token, UUID deliveryId, UUID tenantId, UUID showcaseItemId, String targetUrl) {
        jdbc.update("INSERT INTO lead_outreach_link (token, delivery_id, tenant_id, showcase_item_id, target_url) VALUES (?, ?, ?, ?, ?)",
                token, deliveryId, tenantId, showcaseItemId, targetUrl);
    }

    /** Who a tracked link was sent to (for engagement-based verification). */
    public Optional<String> recipientOfLink(String token) {
        return jdbc.queryForList("""
                SELECT d.recipient_hash FROM lead_outreach_link l JOIN lead_outreach_delivery d ON d.id = l.delivery_id
                WHERE l.token = ?""", String.class, token).stream().findFirst();
    }

    /** Counts the click and returns where the link goes. */
    public Optional<String> click(String token) {
        List<String> target = jdbc.queryForList("""
                UPDATE lead_outreach_link SET click_count = click_count + 1, first_clicked_at = COALESCE(first_clicked_at, NOW())
                WHERE token = ? RETURNING target_url""", String.class, token);
        return target.stream().findFirst();
    }

    // ---- retention ----

    /** Rule 17: raw address, name and company go 30 days after delivery (or after the intent was
     * dropped). Hashes stay, so suppressions and cooldowns keep working. */
    public int purgeBefore(Instant cutoff) {
        Timestamp at = Timestamp.from(cutoff);
        int intents = jdbc.update("""
                UPDATE lead_outreach_intent SET recipient_email = NULL, recipient_name = NULL, company_name = NULL,
                    personal_note = NULL, purged_at = NOW()
                WHERE purged_at IS NULL AND status NOT IN ('QUEUED', 'PENDING') AND COALESCE(decided_at, created_at) < ?""", at);
        int deliveries = jdbc.update("""
                UPDATE lead_outreach_delivery SET recipient_email = NULL, purged_at = NOW()
                WHERE purged_at IS NULL AND delivery_day < ?""", Date.valueOf(cutoff.atZone(java.time.ZoneOffset.UTC).toLocalDate()));
        return intents + deliveries;
    }
}
