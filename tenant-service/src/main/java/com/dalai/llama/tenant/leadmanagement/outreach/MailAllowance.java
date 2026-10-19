package com.dalai.llama.tenant.leadmanagement.outreach;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rule 16: each creator sends their plan's mails a month (calendar month, UTC) for free -- Free
 * 100, Studio 1,000, Pro 3,000 ({@link OutreachPlans}) -- then from paid packs, oldest pack first.
 * One accepted recipient = one mail. */
@Component
@RequiredArgsConstructor
public class MailAllowance {

    /** Where one mail was paid from: free (pack null) or a pack. */
    public record Charge(UUID packId) {
        static final Charge FREE = new Charge(null);
    }

    public record AllowanceView(int freePerMonth, int freeUsedThisMonth, int packMailsLeft) {
        public int left() {
            return Math.max(0, freePerMonth - freeUsedThisMonth) + packMailsLeft;
        }
    }

    private final JdbcTemplate jdbc;
    private final OutreachPlans plans;
    private final Clock clock;

    public AllowanceView view(UUID tenantId) {
        return new AllowanceView(plans.caps(tenantId).monthlyOutreachEmails(), freeUsed(tenantId), packMailsLeft(tenantId));
    }

    /** Must run inside the send's transaction, after {@link #lock}. Empty = nothing left. */
    public Optional<Charge> take(UUID tenantId) {
        if (freeUsed(tenantId) < plans.caps(tenantId).monthlyOutreachEmails()) return Optional.of(Charge.FREE);
        List<UUID> pack = jdbc.queryForList("""
                UPDATE lead_mail_pack SET remaining = remaining - 1
                WHERE id = (SELECT id FROM lead_mail_pack WHERE tenant_id = ? AND remaining > 0
                            ORDER BY purchased_at LIMIT 1 FOR UPDATE)
                RETURNING id""", UUID.class, tenantId);
        return pack.stream().findFirst().map(Charge::new);
    }

    /** Serialises one creator's sends so two at once can't both spend the last free mail. */
    public void lock(UUID tenantId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", tenantId.toString());
    }

    /** Records a pack billing has charged for; a retried purchase (same key) adds nothing. */
    public boolean addPack(UUID tenantId, String idempotencyKey, int quantity, BigDecimal price, String currency) {
        return jdbc.update("""
                INSERT INTO lead_mail_pack (id, tenant_id, idempotency_key, quantity, remaining, price, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING""",
                UUID.randomUUID(), tenantId, idempotencyKey, quantity, quantity, price, currency) == 1;
    }

    private int freeUsed(UUID tenantId) {
        Integer used = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lead_outreach_intent
                WHERE tenant_id = ? AND origin = 'CREATOR' AND mail_pack_id IS NULL
                  AND status NOT IN ('FAILED', 'EXPIRED') AND created_at >= ?""",
                Integer.class, tenantId, Timestamp.from(monthStart()));
        return used == null ? 0 : used;
    }

    private int packMailsLeft(UUID tenantId) {
        Integer left = jdbc.queryForObject("SELECT COALESCE(SUM(remaining), 0) FROM lead_mail_pack WHERE tenant_id = ?",
                Integer.class, tenantId);
        return left == null ? 0 : left;
    }

    private java.time.Instant monthStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
