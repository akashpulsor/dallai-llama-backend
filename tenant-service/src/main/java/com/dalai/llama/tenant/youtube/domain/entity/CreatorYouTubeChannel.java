package com.dalai.llama.tenant.youtube.domain.entity;

import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The YouTube channel a creator linked (V27). One per creator and one creator per channel. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_youtube_channel")
public class CreatorYouTubeChannel {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "channel_id", nullable = false, unique = true, length = 32)
    private String channelId;

    /** Cached API data: refreshed with the videos, never older than 30 days. */
    @Column(name = "channel_title", length = 200)
    private String channelTitle;

    /** Cached API data, same rule as {@link #channelTitle}. Used as the profile avatar until the
     * creator uploads their own. */
    @Column(name = "channel_thumbnail_url", length = 512)
    private String channelThumbnailUrl;

    @Column(name = "uploads_playlist_id", nullable = false, length = 40)
    private String uploadsPlaylistId;

    @Column(name = "verification_code", nullable = false, length = 16)
    private String verificationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChannelStatus status;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
