package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.leadmanagement.audience.MailDomainResolver.MailDomain;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Rule 24: keeps validating email contact points. UNVERIFIED → LIKELY_VALID when the domain takes
 * mail, → INVALID when it can't; our own engagement (a click, a brand sign-in) → VERIFIED and
 * beats everything else (AUDIENCE_PROVIDER §48). Domain answers are cached for a day. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactPointValidator {

    private static final Duration CACHE_FOR = Duration.ofDays(1);
    private static final int BATCH = 200;

    private final MailDomainResolver resolver;
    private final RecipientHasher hasher;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, CachedDomain> cache = new ConcurrentHashMap<>();

    private record CachedDomain(MailDomain result, Instant at) {
    }

    @Scheduled(fixedDelayString = "${outreach.validation-delay-ms:300000}", initialDelayString = "${outreach.validation-delay-ms:300000}")
    public void scheduled() {
        int checked = validatePending();
        if (checked > 0) log.info("Checked {} email contact points", checked);
    }

    /** One batch of UNVERIFIED emails; also fills hashes missing from before the pepper was set. */
    public int validatePending() {
        fillMissingHashes();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, value FROM lead_contact_point WHERE kind = 'EMAIL' AND status = 'UNVERIFIED'
                ORDER BY last_checked_at NULLS FIRST, first_seen_at LIMIT ?""", BATCH);
        for (Map<String, Object> row : rows) {
            apply((UUID) row.get("id"), domain((String) row.get("value")));
        }
        return rows.size();
    }

    /** The send-time re-check (AUDIENCE_PROVIDER §51): false only when we know it can't be delivered. */
    public boolean deliverable(String email) {
        String value = ContactPointValues.normaliseEmail(email);
        if (!ContactPointValues.validEmail(value)) return false;
        List<String> status = jdbc.queryForList("SELECT status FROM lead_contact_point WHERE kind = 'EMAIL' AND value = ?",
                String.class, value);
        if (!status.isEmpty() && ("VERIFIED".equals(status.get(0)) || "LIKELY_VALID".equals(status.get(0)))) return true;
        if (!status.isEmpty() && "INVALID".equals(status.get(0))) return false;
        MailDomain domain = domain(value);
        jdbc.queryForList("SELECT id FROM lead_contact_point WHERE kind = 'EMAIL' AND value = ?", UUID.class, value)
                .forEach(id -> apply(id, domain));
        return domain != MailDomain.NO_MAIL_SERVER;
    }

    public void markVerifiedByHash(String recipientHash, String reason) {
        jdbc.update("""
                UPDATE lead_contact_point SET status = 'VERIFIED', status_reason = ?, last_checked_at = ?
                WHERE kind = 'EMAIL' AND value_hash = ?""", reason, Timestamp.from(clock.instant()), recipientHash);
    }

    public void markVerifiedByEmail(String email, String reason) {
        jdbc.update("""
                UPDATE lead_contact_point SET status = 'VERIFIED', status_reason = ?, last_checked_at = ?
                WHERE kind = 'EMAIL' AND value = ?""", reason, Timestamp.from(clock.instant()), ContactPointValues.normaliseEmail(email));
    }

    private void apply(UUID id, MailDomain domain) {
        Timestamp now = Timestamp.from(clock.instant());
        switch (domain) {
            case ACCEPTS_MAIL -> jdbc.update("""
                    UPDATE lead_contact_point SET status = 'LIKELY_VALID', status_reason = 'DOMAIN_ACCEPTS_MAIL', last_checked_at = ?
                    WHERE id = ? AND status = 'UNVERIFIED'""", now, id);
            case NO_MAIL_SERVER -> jdbc.update("""
                    UPDATE lead_contact_point SET status = 'INVALID', status_reason = 'NO_MAIL_SERVER', last_checked_at = ?
                    WHERE id = ? AND status IN ('UNVERIFIED', 'LIKELY_VALID')""", now, id);
            default -> jdbc.update("UPDATE lead_contact_point SET last_checked_at = ? WHERE id = ?", now, id);
        }
    }

    private MailDomain domain(String email) {
        String domain = ContactPointValues.domainOf(email);
        CachedDomain cached = cache.get(domain);
        if (cached != null && cached.at().plus(CACHE_FOR).isAfter(clock.instant())) return cached.result();
        MailDomain result = resolver.check(domain);
        if (result != MailDomain.UNKNOWN) cache.put(domain, new CachedDomain(result, clock.instant()));
        return result;
    }

    private void fillMissingHashes() {
        if (!hasher.configured()) return;
        jdbc.queryForList("SELECT id, value FROM lead_contact_point WHERE kind = 'EMAIL' AND value_hash IS NULL LIMIT ?", BATCH)
                .forEach(row -> jdbc.update("UPDATE lead_contact_point SET value_hash = ? WHERE id = ?",
                        hasher.hash((String) row.get("value")), row.get("id")));
    }
}
