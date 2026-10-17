package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The routing setting, the creators who take routed requests, and each request's offers (V46). */
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

    /** Creators who take routed requests: handle and whether they are on. */
    public List<String> acceptingHandles() {
        return jdbc.queryForList("""
                SELECT p.handle FROM request_routing_creator r JOIN creator_public_profile p ON p.tenant_id = r.tenant_id
                WHERE r.accepting AND p.status = 'ACTIVE' ORDER BY p.handle""", String.class);
    }

    /**
     * Active, accepting creators who haven't passed on this request: those covering the industry
     * first (all of them when none does), then whoever has waited longest for one.
     */
    public List<UUID> candidates(UUID inquiryId, ShowcaseIndustry industry) {
        String code = industry == null ? null : industry.name();
        return jdbc.queryForList("""
                SELECT r.tenant_id FROM request_routing_creator r
                JOIN creator_public_profile p ON p.tenant_id = r.tenant_id
                WHERE r.accepting AND p.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM lead_inquiry_offer o
                                  WHERE o.inquiry_id = ? AND o.tenant_id = r.tenant_id AND o.status = 'DECLINED')
                ORDER BY CASE WHEN CAST(? AS VARCHAR) IS NULL THEN 0
                              WHEN EXISTS (SELECT 1 FROM creator_profile_industry i
                                           WHERE i.tenant_id = r.tenant_id AND i.industry = ?) THEN 0
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
                WHERE o.tenant_id = ? AND o.status = 'OFFERED' AND q.tenant_id IS NULL""", UUID.class, tenantId);
    }

    /** Gives the request to the creator if nobody has it yet; false when someone was quicker. */
    public boolean claim(UUID inquiryId, UUID tenantId) {
        return jdbc.update("""
                UPDATE lead_brand_inquiry SET tenant_id = ?, status = 'NEW', updated_at = NOW()
                WHERE id = ? AND tenant_id IS NULL""", tenantId, inquiryId) == 1;
    }

    public void touchRouted(UUID tenantId, OffsetDateTime now) {
        jdbc.update("UPDATE request_routing_creator SET last_routed_at = ? WHERE tenant_id = ?", Timestamp.from(now.toInstant()), tenantId);
    }

    /** Routed requests nobody has yet, oldest first: the ops queue. */
    public List<UUID> unassigned() {
        return jdbc.queryForList("""
                SELECT id FROM lead_brand_inquiry WHERE tenant_id IS NULL AND source = 'LANDING'
                  AND status <> 'DECLINED' ORDER BY created_at""", UUID.class);
    }
}
