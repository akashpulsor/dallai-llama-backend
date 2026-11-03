package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The routing setting, the creators who take routed requests, and each request's offers (V46).
 * Every creator with an active profile takes routed requests unless ops switched them off. */
@Repository
@RequiredArgsConstructor
public class RequestRoutingStore {

    public enum OfferStatus { OFFERED, ASSIGNED, DECLINED, WITHDRAWN }

    private final JdbcTemplate jdbc;

    public RoutingStrategy strategy() {
        return RoutingStrategy.valueOf(jdbc.queryForObject("SELECT strategy FROM request_routing_setting WHERE id = 1", String.class));
    }

    public void setStrategy(RoutingStrategy strategy) {
        jdbc.update("UPDATE request_routing_setting SET strategy = ?, updated_at = NOW() WHERE id = 1", strategy.name());
    }

    public void setAccepting(UUID tenantId, boolean accepting) {
        jdbc.update("""
                INSERT INTO request_routing_creator (tenant_id, accepting) VALUES (?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET accepting = EXCLUDED.accepting, updated_at = NOW()""", tenantId, accepting);
    }

    /** One active creator as ops see them: on for routed requests or switched off. */
    public record RoutedCreator(String handle, String displayName, boolean accepting) {
    }

    /** Every active creator, and whether they take routed requests. */
    public List<RoutedCreator> creators() {
        return jdbc.query("""
                SELECT p.handle, p.display_name, COALESCE(r.accepting, TRUE) AS accepting
                FROM creator_public_profile p LEFT JOIN request_routing_creator r ON r.tenant_id = p.tenant_id
                WHERE p.status = 'ACTIVE' ORDER BY p.handle""",
                (rs, n) -> new RoutedCreator(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
    }

    /**
     * Active, accepting creators who haven't passed on this request: those covering the industry
     * first (all of them when none does), then whoever has waited longest for one.
     */
    public List<UUID> candidates(UUID inquiryId, ShowcaseIndustry industry) {
        String code = industry == null ? null : industry.name();
        return jdbc.queryForList("""
                SELECT p.tenant_id FROM creator_public_profile p
                LEFT JOIN request_routing_creator r ON r.tenant_id = p.tenant_id
                WHERE COALESCE(r.accepting, TRUE) AND p.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM lead_inquiry_offer o
                                  WHERE o.inquiry_id = ? AND o.tenant_id = p.tenant_id AND o.status = 'DECLINED')
                ORDER BY CASE WHEN CAST(? AS VARCHAR) IS NULL THEN 0
                              WHEN EXISTS (SELECT 1 FROM creator_profile_industry i
                                           WHERE i.tenant_id = p.tenant_id AND i.industry = ?) THEN 0
                              ELSE 1 END,
                         r.last_routed_at NULLS FIRST, p.created_at""", UUID.class, inquiryId, code, code);
    }

    public void offer(UUID inquiryId, UUID tenantId, OfferStatus status, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO lead_inquiry_offer (inquiry_id, tenant_id, status, offered_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (inquiry_id, tenant_id) DO UPDATE SET status = EXCLUDED.status, answered_at = NULL""",
                inquiryId, tenantId, status.name(), Timestamp.from(now.toInstant()));
    }

    public void answer(UUID inquiryId, UUID tenantId, OfferStatus status, OffsetDateTime now) {
        jdbc.update("UPDATE lead_inquiry_offer SET status = ?, answered_at = ? WHERE inquiry_id = ? AND tenant_id = ?",
                status.name(), Timestamp.from(now.toInstant()), inquiryId, tenantId);
    }

    /** Every open offer of this request other than {@code keep}'s is withdrawn. */
    public void withdrawOthers(UUID inquiryId, UUID keep, OffsetDateTime now) {
        jdbc.update("""
                UPDATE lead_inquiry_offer SET status = 'WITHDRAWN', answered_at = ?
                WHERE inquiry_id = ? AND tenant_id <> ? AND status = 'OFFERED'""", Timestamp.from(now.toInstant()), inquiryId, keep);
    }

    /** Every open offer of this request is withdrawn (it expired). */
    public void withdrawAll(UUID inquiryId, OffsetDateTime now) {
        jdbc.update("UPDATE lead_inquiry_offer SET status = 'WITHDRAWN', answered_at = ? WHERE inquiry_id = ? AND status = 'OFFERED'",
                Timestamp.from(now.toInstant()), inquiryId);
    }

    /** Who holds the request now; null when nobody does. */
    public UUID holder(UUID inquiryId) {
        return jdbc.queryForObject("SELECT tenant_id FROM lead_brand_inquiry WHERE id = ?", UUID.class, inquiryId);
    }

    public boolean isOffered(UUID inquiryId, UUID tenantId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lead_inquiry_offer WHERE inquiry_id = ? AND tenant_id = ? AND status = 'OFFERED'""",
                Integer.class, inquiryId, tenantId);
        return n != null && n > 0;
    }

    /** Requests waiting for this creator to accept. */
    public List<UUID> offeredTo(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT o.inquiry_id FROM lead_inquiry_offer o JOIN lead_brand_inquiry q ON q.id = o.inquiry_id
                WHERE o.tenant_id = ? AND o.status = 'OFFERED' AND (q.tenant_id IS NULL OR q.provisional)""", UUID.class, tenantId);
    }

    /** Gives the request to the creator if nobody has it yet; false when someone was quicker. */
    public boolean claim(UUID inquiryId, UUID tenantId) {
        return jdbc.update("""
                UPDATE lead_brand_inquiry SET tenant_id = ?, status = 'NEW', updated_at = NOW()
                WHERE id = ? AND tenant_id IS NULL""", tenantId, inquiryId) == 1;
    }

    /** Dalaillama's own creator account: the active creator profile of the account with this email. */
    public java.util.Optional<UUID> houseCreator(String email) {
        if (email == null || email.isBlank()) return java.util.Optional.empty();
        return jdbc.queryForList("""
                SELECT p.tenant_id FROM creator_public_profile p JOIN tenants t ON t.id = p.tenant_id
                WHERE LOWER(t.admin_user_email) = LOWER(?) OR LOWER(t.primary_contact_email) = LOWER(?)
                ORDER BY (p.status = 'ACTIVE') DESC, p.created_at LIMIT 1""", UUID.class, email.trim(), email.trim()).stream().findFirst();
    }

    /** Dalaillama's account holds a request nobody has, until a creator takes it. */
    public boolean holdForHouse(UUID inquiryId, UUID house) {
        return jdbc.update("""
                UPDATE lead_brand_inquiry SET tenant_id = ?, provisional = TRUE, status = 'NEW', updated_at = NOW()
                WHERE id = ? AND tenant_id IS NULL""", house, inquiryId) == 1;
    }

    /** Paid: offered to creators until {@code until}. */
    public void startMatching(UUID inquiryId, OffsetDateTime until) {
        jdbc.update("UPDATE lead_brand_inquiry SET matching_until = ?, updated_at = NOW() WHERE id = ? AND provisional",
                Timestamp.from(until.toInstant()), inquiryId);
    }

    /** A creator (or ops for one) takes a request Dalaillama holds; false when someone was quicker or it already ended. */
    public boolean takeFromHouse(UUID inquiryId, UUID tenantId) {
        return jdbc.update("""
                UPDATE lead_brand_inquiry SET tenant_id = ?, provisional = FALSE, matching_until = NULL, updated_at = NOW()
                WHERE id = ? AND provisional""", tenantId, inquiryId) == 1;
    }

    /** Nobody took a paid request: Dalaillama's account keeps it for good. False when it already ended. */
    public boolean keepForHouse(UUID inquiryId) {
        return jdbc.update("""
                UPDATE lead_brand_inquiry SET provisional = FALSE, matching_until = NULL, updated_at = NOW()
                WHERE id = ? AND provisional AND paid_at IS NOT NULL""", inquiryId) == 1;
    }

    /** Paid requests whose matching time is up. */
    public List<UUID> matchingEnded(OffsetDateTime now) {
        return jdbc.queryForList("SELECT id FROM lead_brand_inquiry WHERE provisional AND matching_until < ? ORDER BY matching_until",
                UUID.class, Timestamp.from(now.toInstant()));
    }

    public int openOffers(UUID inquiryId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM lead_inquiry_offer WHERE inquiry_id = ? AND status = 'OFFERED'",
                Integer.class, inquiryId);
        return n == null ? 0 : n;
    }

    /** Whether this creator was ever offered (or passed on) the request. */
    public boolean everOffered(UUID inquiryId, UUID tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM lead_inquiry_offer WHERE inquiry_id = ? AND tenant_id = ?",
                Integer.class, inquiryId, tenantId);
        return n != null && n > 0;
    }

    public void touchRouted(UUID tenantId, OffsetDateTime now) {
        // A creator on by default has no row yet: this makes it.
        jdbc.update("""
                INSERT INTO request_routing_creator (tenant_id, accepting, last_routed_at) VALUES (?, TRUE, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET last_routed_at = EXCLUDED.last_routed_at""",
                tenantId, Timestamp.from(now.toInstant()));
    }

    /** Routed requests nobody has yet, oldest first: the ops queue. */
    public List<UUID> unassigned() {
        return waiting().stream().map(WaitingRequest::id).toList();
    }

    /** A request in the ops queue, with what ops need to pick its creator. */
    public record WaitingRequest(UUID id, String brand, String email, ShowcaseIndustry industry, String message,
                                 OffsetDateTime createdAt,
                                 /* Held by Dalaillama's account: paid when, and offered to creators until when. */
                                 OffsetDateTime paidAt, OffsetDateTime matchingUntil) {
    }

    /** A request a creator holds whose brief the brand hasn't paid yet: ops can expire it. */
    public record UnpaidRequest(UUID id, String brand, String email, String creatorHandle, String status, OffsetDateTime createdAt) {
    }

    public List<UnpaidRequest> unpaid() {
        return jdbc.query("""
                SELECT q.id, COALESCE(c.company_name, c.contact_name, c.email), c.email, p.handle, q.status, q.created_at
                FROM lead_brand_inquiry q JOIN lead_brand_contact c ON c.id = q.brand_contact_id
                JOIN creator_public_profile p ON p.tenant_id = q.tenant_id
                WHERE q.paid_at IS NULL AND q.status IN ('NEW', 'VIEWED', 'CONVERTED') ORDER BY q.created_at""",
                (rs, n) -> new UnpaidRequest(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class)));
    }

    public List<WaitingRequest> waiting() {
        return jdbc.query("""
                SELECT q.id, COALESCE(c.company_name, c.contact_name, c.email), c.email, q.industry, q.message, q.created_at,
                       q.paid_at, q.matching_until
                FROM lead_brand_inquiry q JOIN lead_brand_contact c ON c.id = q.brand_contact_id
                WHERE q.source = 'LANDING' AND ((q.tenant_id IS NULL AND q.status IN ('NEW', 'VIEWED'))
                                                OR (q.provisional AND q.status <> 'EXPIRED'))
                ORDER BY q.created_at""",
                (rs, n) -> new WaitingRequest(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4) == null ? null : ShowcaseIndustry.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class)));
    }
}
