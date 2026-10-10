package com.dalai.llama.tenant.leadmanagement.outreach;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Rule 28's numbers, grouped by template or by audience. Clicks belong to the intent whose
 * delivery carried the link (for a digest, the card for that creator's film). Our own data only. */
@Repository
@RequiredArgsConstructor
public class OutreachAnalyticsStore {

    /** The intent column a breakdown groups on; never user input. */
    public enum GroupBy {
        TEMPLATE("email_template_id"), AUDIENCE("audience_id");

        private final String column;

        GroupBy(String column) {
            this.column = column;
        }
    }

    public record Counts(int sent, int queued, int clicks, int requests) {
        static final Counts NONE = new Counts(0, 0, 0, 0);
    }

    private static final String LINKS = """
            FROM lead_outreach_intent i
            JOIN lead_outreach_delivery d ON d.id = i.delivery_id
            JOIN lead_outreach_link l ON l.delivery_id = d.id AND l.tenant_id = i.tenant_id
                 AND (d.kind = 'CREATOR_MAIL' OR l.showcase_item_id = i.showcase_item_id)
            """;

    private final JdbcTemplate jdbc;

    public Map<UUID, Counts> by(GroupBy group, UUID tenantId, Instant since) {
        String col = "i." + group.column;
        Timestamp at = Timestamp.from(since);
        Map<UUID, int[]> acc = new HashMap<>();
        jdbc.query("SELECT " + col + ", COUNT(*) FILTER (WHERE i.status IN ('SENT', 'DIGESTED')),"
                        + " COUNT(*) FILTER (WHERE i.status IN ('QUEUED', 'PENDING'))"
                        + " FROM lead_outreach_intent i WHERE i.tenant_id = ? AND i.created_at > ? AND " + col + " IS NOT NULL"
                        + " GROUP BY " + col,
                rs -> {
                    int[] a = acc.computeIfAbsent(rs.getObject(1, UUID.class), k -> new int[4]);
                    a[0] = rs.getInt(2);
                    a[1] = rs.getInt(3);
                }, tenantId, at);
        jdbc.query("SELECT " + col + ", COALESCE(SUM(l.click_count), 0) " + LINKS
                        + " WHERE i.tenant_id = ? AND i.created_at > ? AND " + col + " IS NOT NULL GROUP BY " + col,
                rs -> { acc.computeIfAbsent(rs.getObject(1, UUID.class), k -> new int[4])[2] = rs.getInt(2); }, tenantId, at);
        jdbc.query("SELECT " + col + ", COUNT(DISTINCT q.id) " + LINKS
                        + " JOIN lead_brand_inquiry q ON q.attribution_token = l.token"
                        + " WHERE i.tenant_id = ? AND i.created_at > ? AND " + col + " IS NOT NULL GROUP BY " + col,
                rs -> { acc.computeIfAbsent(rs.getObject(1, UUID.class), k -> new int[4])[3] = rs.getInt(2); }, tenantId, at);
        Map<UUID, Counts> out = new HashMap<>();
        acc.forEach((id, a) -> out.put(id, new Counts(a[0], a[1], a[2], a[3])));
        return out;
    }

    public int queued(UUID tenantId, Instant since) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM lead_outreach_intent WHERE tenant_id = ? AND status IN ('QUEUED', 'PENDING') AND created_at > ?""",
                Integer.class, tenantId, Timestamp.from(since));
        return n == null ? 0 : n;
    }
}
