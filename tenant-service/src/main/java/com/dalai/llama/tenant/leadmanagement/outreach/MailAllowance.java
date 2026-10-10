package com.dalai.llama.tenant.leadmanagement.outreach;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rule 16: each creator sends {@code freePerWeek} mails a week (ISO week, UTC) for free, then
 * from paid packs, oldest pack first. One accepted recipient = one mail. */
@Component
@RequiredArgsConstructor
public class MailAllowance {

    /** Where one mail was paid from: free (pack null) or a pack. */
    public record Charge(UUID packId) {
        static final Charge FREE = new Charge(null);
    }

    public record AllowanceView(int freePerWeek, int freeUsedThisWeek, int packMailsLeft) {
        public int left() {
            return Math.max(0, freePerWeek - freeUsedThisWeek) + packMailsLeft;
        }
    }

    private final JdbcTemplate jdbc;
    private final OutreachProperties properties;
    private final Clock clock;

    public AllowanceView view(UUID tenantId) {
        return new AllowanceView(properties.freePerWeek(), freeUsed(tenantId), packMailsLeft(tenantId));
    }

    /** Must run inside the send's transaction, after {@link #lock}. Empty = nothing left. */
    public Optional<Charge> take(UUID tenantId) {
        if (freeUsed(tenantId) < properties.freePerWeek()) return Optional.of(Charge.FREE);
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
                WHERE tenant_id = ? AND origin = 'CREATOR' AND mail_pack_id IS NULL AND status <> 'FAILED' AND created_at >= ?""",
                Integer.class, tenantId, Timestamp.from(weekStart()));
        return used == null ? 0 : used;
    }

    private int packMailsLeft(UUID tenantId) {
        Integer left = jdbc.queryForObject("SELECT COALESCE(SUM(remaining), 0) FROM lead_mail_pack WHERE tenant_id = ?",
                Integer.class, tenantId);
        return left == null ? 0 : left;
    }

    private java.time.Instant weekStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
