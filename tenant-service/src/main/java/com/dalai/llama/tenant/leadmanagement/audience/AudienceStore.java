package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues.Kind;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Audiences, creator leads and contact points (V34). Every creator-facing query filters by
 * tenant: a creator only ever reaches their own leads (PLATFORM.md rule 5). */
@Repository
@RequiredArgsConstructor
public class AudienceStore {

    public record AudienceRow(UUID id, String name, Instant createdAt, int leads, int reachable) {
    }

    public record LeadRow(UUID id, String name, String designation, String company, ShowcaseIndustry industry, String website, Instant createdAt) {
    }

    public record PointRow(UUID leadId, UUID contactPointId, Kind kind, String value, String status, boolean unsubscribed) {
    }

    /** The one email an audience send uses for a lead. */
    public record Recipient(UUID leadId, String email, String name, String company) {
    }

    /** Reachable = has an email that isn't INVALID and isn't unsubscribed. */
    private static final String REACHABLE = """
            EXISTS (SELECT 1 FROM lead_creator_lead_contact_point lc
                    JOIN lead_contact_point cp ON cp.id = lc.contact_point_id
                    LEFT JOIN lead_suppression s ON s.recipient_hash = cp.value_hash
                    WHERE lc.lead_id = m.lead_id AND cp.kind = 'EMAIL' AND cp.status <> 'INVALID' AND s.recipient_hash IS NULL)""";

    private final JdbcTemplate jdbc;

    // ---- audiences ----

    public List<AudienceRow> audiences(UUID tenantId) {
        return jdbc.query("""
                SELECT a.id, a.name, a.created_at, COUNT(m.lead_id), COUNT(m.lead_id) FILTER (WHERE\s""" + REACHABLE + """
                )
                FROM lead_saved_audience a LEFT JOIN lead_saved_audience_member m ON m.audience_id = a.id
                WHERE a.tenant_id = ? GROUP BY a.id ORDER BY a.created_at DESC""",
                (rs, n) -> new AudienceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getInt(4), rs.getInt(5)), tenantId);
    }

    public boolean owns(UUID tenantId, UUID audienceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM lead_saved_audience WHERE id = ? AND tenant_id = ?)",
                Boolean.class, audienceId, tenantId));
    }

    public Optional<String> audienceName(UUID tenantId, UUID audienceId) {
        return jdbc.queryForList("SELECT name FROM lead_saved_audience WHERE id = ? AND tenant_id = ?", String.class, audienceId, tenantId)
                .stream().findFirst();
    }

    public boolean nameTaken(UUID tenantId, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM lead_saved_audience WHERE tenant_id = ? AND lower(name) = lower(?))", Boolean.class, tenantId, name));
    }

    public UUID createAudience(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lead_saved_audience (id, tenant_id, name) VALUES (?, ?, ?)", id, tenantId, name);
        return id;
    }

    /** Removes the list and its memberships; the leads stay with the creator. */
    public void deleteAudience(UUID tenantId, UUID audienceId) {
        jdbc.update("DELETE FROM lead_saved_audience WHERE id = ? AND tenant_id = ?", audienceId, tenantId);
    }

    public void addMember(UUID audienceId, UUID leadId) {
        jdbc.update("INSERT INTO lead_saved_audience_member (audience_id, lead_id) VALUES (?, ?) ON CONFLICT DO NOTHING", audienceId, leadId);
    }

    public int removeMember(UUID tenantId, UUID audienceId, UUID leadId) {
        return jdbc.update("""
                DELETE FROM lead_saved_audience_member m USING lead_saved_audience a
                WHERE m.audience_id = a.id AND a.tenant_id = ? AND m.audience_id = ? AND m.lead_id = ?""", tenantId, audienceId, leadId);
    }

    public int memberCount(UUID audienceId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM lead_saved_audience_member WHERE audience_id = ?", Integer.class, audienceId);
        return n == null ? 0 : n;
    }

    // ---- contact points and leads ----

    /** Inserts or touches a platform contact point; returns its id. An existing row keeps its status. */
    public UUID upsertContactPoint(Kind kind, String value, String hash, String status, String reason) {
        return jdbc.queryForObject("""
                INSERT INTO lead_contact_point (id, kind, value, value_hash, status, status_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (kind, value) DO UPDATE SET last_seen_at = NOW(),
                    value_hash = COALESCE(lead_contact_point.value_hash, EXCLUDED.value_hash)
                RETURNING id""", UUID.class, UUID.randomUUID(), kind.name(), value, hash, status, reason);
    }

    /** This creator's leads that already own any of these contact points, oldest first. */
    public List<UUID> leadsOwning(UUID tenantId, Collection<UUID> contactPointIds) {
        if (contactPointIds.isEmpty()) return List.of();
        String in = contactPointIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[contactPointIds.size() + 1];
        args[0] = tenantId;
        int i = 1;
        for (UUID id : contactPointIds) args[i++] = id;
        return jdbc.queryForList("""
                SELECT l.id FROM lead_creator_lead l
                WHERE l.tenant_id = ? AND l.id IN (SELECT lead_id FROM lead_creator_lead_contact_point WHERE contact_point_id IN (""" + in + """
                )) ORDER BY l.created_order""", UUID.class, args);
    }

    public UUID createLead(UUID tenantId, String name, String designation, String company, ShowcaseIndustry industry, String website,
                           Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lead_creator_lead (id, tenant_id, display_name, designation, company_name, industry, website_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, tenantId, name, designation, company, industry == null ? null : industry.name(), website,
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    /** Fills only what the lead doesn't have yet: a later row never wipes earlier details. */
    public void fillBlanks(UUID leadId, String name, String designation, String company, ShowcaseIndustry industry, String website) {
        jdbc.update("""
                UPDATE lead_creator_lead SET display_name = COALESCE(display_name, ?), designation = COALESCE(designation, ?),
                    company_name = COALESCE(company_name, ?), industry = COALESCE(industry, ?), website_url = COALESCE(website_url, ?),
                    updated_at = NOW()
                WHERE id = ?""", name, designation, company, industry == null ? null : industry.name(), website, leadId);
    }

    /** Folds {@code others} into {@code keep}: contact points, audiences, source rows and intents move;
     * the emptied leads go. */
    public void merge(UUID tenantId, UUID keep, List<UUID> others) {
        for (UUID other : others) {
            jdbc.update("UPDATE lead_creator_lead_contact_point SET lead_id = ? WHERE lead_id = ? AND tenant_id = ?", keep, other, tenantId);
            jdbc.update("""
                    INSERT INTO lead_saved_audience_member (audience_id, lead_id, added_at)
                    SELECT audience_id, ?, added_at FROM lead_saved_audience_member WHERE lead_id = ? ON CONFLICT DO NOTHING""", keep, other);
            jdbc.update("UPDATE lead_creator_lead_source SET lead_id = ? WHERE lead_id = ?", keep, other);
            jdbc.update("UPDATE lead_outreach_intent SET lead_id = ? WHERE lead_id = ?", keep, other);
            jdbc.queryForList("SELECT display_name, designation, company_name, industry, website_url FROM lead_creator_lead WHERE id = ?", other)
                    .forEach(r -> fillBlanks(keep, (String) r.get("display_name"), (String) r.get("designation"), (String) r.get("company_name"),
                            r.get("industry") == null ? null : ShowcaseIndustry.valueOf((String) r.get("industry")),
                            (String) r.get("website_url")));
            jdbc.update("DELETE FROM lead_creator_lead WHERE id = ? AND tenant_id = ?", other, tenantId);
        }
    }

    /** Links a contact point to the lead; true if it's new for this creator. */
    public boolean link(UUID tenantId, UUID leadId, UUID contactPointId) {
        int updated = jdbc.update("""
                UPDATE lead_creator_lead_contact_point SET times_seen = times_seen + 1, last_seen_at = NOW()
                WHERE tenant_id = ? AND contact_point_id = ?""", tenantId, contactPointId);
        if (updated > 0) return false;
        jdbc.update("INSERT INTO lead_creator_lead_contact_point (lead_id, contact_point_id, tenant_id) VALUES (?, ?, ?)",
                leadId, contactPointId, tenantId);
        return true;
    }

    public int unlink(UUID tenantId, UUID leadId, UUID contactPointId) {
        return jdbc.update("DELETE FROM lead_creator_lead_contact_point WHERE tenant_id = ? AND lead_id = ? AND contact_point_id = ?",
                tenantId, leadId, contactPointId);
    }

    // ---- import bookkeeping ----

    public record BatchCounts(int total, int imported, int rejected, int leadsCreated, int leadsMerged, int contactPointsAdded) {
    }

    public UUID insertBatch(UUID tenantId, UUID audienceId, String fileName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lead_import_batch (id, tenant_id, audience_id, file_name, rows_total, rows_imported, rows_rejected,
                    leads_created, leads_merged, contact_points_added)
                VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 0)""", id, tenantId, audienceId, fileName);
        return id;
    }

    public void finishBatch(UUID batchId, BatchCounts c) {
        jdbc.update("""
                UPDATE lead_import_batch SET rows_total = ?, rows_imported = ?, rows_rejected = ?, leads_created = ?, leads_merged = ?,
                    contact_points_added = ? WHERE id = ?""",
                c.total(), c.imported(), c.rejected(), c.leadsCreated(), c.leadsMerged(), c.contactPointsAdded(), batchId);
    }

    public void insertSource(UUID batchId, UUID tenantId, UUID leadId, int rowNumber, String raw, String rejectedReason) {
        jdbc.update("""
                INSERT INTO lead_creator_lead_source (id, import_batch_id, tenant_id, lead_id, row_number, raw_row, rejected_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), batchId, tenantId, leadId, rowNumber, raw, rejectedReason);
    }

    // ---- reading leads ----

    public int countLeads(UUID tenantId, UUID audienceId, String q) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) " + leadsFrom(q), Integer.class, leadsArgs(tenantId, audienceId, q));
        return n == null ? 0 : n;
    }

    public List<LeadRow> leads(UUID tenantId, UUID audienceId, String q, int page, int size) {
        Object[] base = leadsArgs(tenantId, audienceId, q);
        Object[] args = new Object[base.length + 2];
        System.arraycopy(base, 0, args, 0, base.length);
        args[base.length] = size;
        args[base.length + 1] = page * size;
        return jdbc.query("SELECT l.id, l.display_name, l.designation, l.company_name, l.industry, l.website_url, l.created_at " + leadsFrom(q)
                        + " ORDER BY l.created_at DESC, l.id LIMIT ? OFFSET ?",
                (rs, n) -> new LeadRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5) == null ? null : ShowcaseIndustry.valueOf(rs.getString(5)), rs.getString(6),
                        rs.getTimestamp(7).toInstant()), args);
    }

    private static String leadsFrom(String q) {
        String from = """
                FROM lead_saved_audience_member m JOIN lead_creator_lead l ON l.id = m.lead_id
                WHERE l.tenant_id = ? AND m.audience_id = ?""";
        if (q == null || q.isBlank()) return from;
        return from + "\n" + """
                AND (l.display_name ILIKE ? OR l.company_name ILIKE ? OR EXISTS (
                    SELECT 1 FROM lead_creator_lead_contact_point lc JOIN lead_contact_point cp ON cp.id = lc.contact_point_id
                    WHERE lc.lead_id = l.id AND cp.value ILIKE ?))""";
    }

    private static Object[] leadsArgs(UUID tenantId, UUID audienceId, String q) {
        if (q == null || q.isBlank()) return new Object[]{tenantId, audienceId};
        String like = "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
        return new Object[]{tenantId, audienceId, like, like, like};
    }

    public List<PointRow> points(UUID tenantId, Collection<UUID> leadIds) {
        if (leadIds.isEmpty()) return List.of();
        String in = leadIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[leadIds.size() + 1];
        args[0] = tenantId;
        int i = 1;
        for (UUID id : leadIds) args[i++] = id;
        return jdbc.query("""
                SELECT lc.lead_id, cp.id, cp.kind, cp.value, cp.status, s.recipient_hash IS NOT NULL
                FROM lead_creator_lead_contact_point lc JOIN lead_contact_point cp ON cp.id = lc.contact_point_id
                LEFT JOIN lead_suppression s ON s.recipient_hash = cp.value_hash
                WHERE lc.tenant_id = ? AND lc.lead_id IN (""" + in + """
                ) ORDER BY lc.last_seen_at DESC""",
                (rs, n) -> new PointRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Kind.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6)), args);
    }

    /** Rule 26: one email per lead: VERIFIED, then LIKELY_VALID, then UNVERIFIED; unsubscribed last;
     * most recently seen first; never INVALID. Leads with no usable email are left out. */
    public List<Recipient> recipients(UUID tenantId, UUID audienceId, int limit) {
        return jdbc.query("""
                SELECT DISTINCT ON (l.id) l.id, cp.value, l.display_name, l.company_name
                FROM lead_saved_audience_member m
                JOIN lead_creator_lead l ON l.id = m.lead_id
                JOIN lead_creator_lead_contact_point lc ON lc.lead_id = l.id
                JOIN lead_contact_point cp ON cp.id = lc.contact_point_id AND cp.kind = 'EMAIL' AND cp.status <> 'INVALID'
                LEFT JOIN lead_suppression s ON s.recipient_hash = cp.value_hash
                WHERE m.audience_id = ? AND l.tenant_id = ?
                ORDER BY l.id, (s.recipient_hash IS NOT NULL),
                         CASE cp.status WHEN 'VERIFIED' THEN 0 WHEN 'LIKELY_VALID' THEN 1 ELSE 2 END, lc.last_seen_at DESC
                LIMIT ?""",
                (rs, n) -> new Recipient(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
                audienceId, tenantId, limit);
    }
}
