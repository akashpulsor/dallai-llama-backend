package com.dalai.llama.tenant.showcase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Shared Postgres container and seed helpers for the showcase database tests. Subclasses add
 * {@code @Testcontainers(disabledWithoutDocker = true)}, {@code @DataJpaTest} and their imports. */
public abstract class ShowcaseDbTestSupport {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected static void wipe(JdbcTemplate jdbc) {
        for (String table : new String[]{"youtube_publish_job", "extension_token", "lead_outreach_link", "lead_outreach_intent", "lead_outreach_delivery", "lead_mail_pack",
                "lead_creator_lead_source", "lead_import_batch", "lead_saved_audience_member", "lead_creator_lead_contact_point",
                "lead_contact_point", "lead_creator_lead", "lead_saved_audience",
                "lead_suppression", "lead_brand_inquiry", "creator_follower", "showcase_like", "lead_brand_sign_in",
                "lead_brand_contact", "official_upload_job", "showcase_play", "showcase_item", "youtube_video",
                "creator_youtube_channel", "creator_handle_history", "creator_profile_industry", "creator_public_profile"}) {
            jdbc.update("DELETE FROM " + table);
        }
        // The seeded global templates (V34) stay; anything a test created goes.
        jdbc.update("DELETE FROM lead_email_template WHERE tenant_id IS NOT NULL OR id::text NOT LIKE '00000000-0000-4000-8000-0000000000a%'");
    }

    protected static UUID tenant(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, primary_contact_name, primary_contact_email) VALUES (?, ?, ?, ?, ?)",
                id, name, "t-" + id.toString().substring(0, 8), "Owner", "owner@example.com");
        return id;
    }

    protected static void verifiedChannel(JdbcTemplate jdbc, UUID tenantId, String channelId, Instant at) {
        jdbc.update("""
                INSERT INTO creator_youtube_channel (tenant_id, channel_id, channel_title, channel_thumbnail_url,
                    uploads_playlist_id, verification_code, status, verified_at, fetched_at)
                VALUES (?, ?, 'Channel', 'https://yt3/c.jpg', ?, 'dalai-XXXXXX', 'VERIFIED', ?, ?)""",
                tenantId, channelId, "UU" + channelId, Timestamp.from(at), Timestamp.from(at));
    }

    protected static void video(JdbcTemplate jdbc, String id, String channelId, int w, int h, Instant fetchedAt) {
        jdbc.update("""
                INSERT INTO youtube_video (video_id, channel_id, title, thumbnail_url, published_at, duration_seconds,
                    aspect_w, aspect_h, privacy_status, embeddable, age_restricted, made_for_kids, fetched_at)
                VALUES (?, ?, ?, ?, ?, 30, ?, ?, 'public', TRUE, FALSE, FALSE, ?)""",
                id, channelId, "Video " + id, "https://i/" + id + ".jpg", Timestamp.from(fetchedAt), w, h, Timestamp.from(fetchedAt));
    }
}
