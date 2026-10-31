package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Each creator's public profile page and how many videos show on it, for ops (the Tenants list,
 * and mail to creators whose profile has no video yet). A creator with no profile row has neither. */
@Component
@RequiredArgsConstructor
public class CreatorProfileLinks {

    public record Link(String handle, String url, int liveVideos) {
    }

    private final JdbcTemplate jdbc;
    private final ShowcaseProperties properties;

    public Map<UUID, Link> all() {
        Map<UUID, Link> links = new HashMap<>();
        jdbc.query("""
                SELECT p.tenant_id, p.handle,
                       COUNT(s.id) FILTER (WHERE s.status = 'LIVE' AND NOT s.hidden_by_ops)
                FROM creator_public_profile p LEFT JOIN showcase_item s ON s.tenant_id = p.tenant_id
                GROUP BY p.tenant_id, p.handle""",
                rs -> {
                    String handle = rs.getString(2);
                    links.put(rs.getObject(1, UUID.class), new Link(handle, properties.publicBaseUrl() + "/c/" + handle, rs.getInt(3)));
                });
        return links;
    }
}
