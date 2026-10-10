package com.dalai.llama.tenant.youtube.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Our cache of one YouTube video (V27). The id is ours to keep; everything else is API data
 * that {@link com.dalai.llama.tenant.youtube.service.YouTubeRefreshJob} refreshes before it is
 * 30 days old. Stores raw facts only: whether a video may be shown is decided on read by
 * {@link com.dalai.llama.tenant.youtube.service.VideoEligibility}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "youtube_video")
public class YouTubeVideo {

    @Id
    @Column(name = "video_id", length = 16)
    private String videoId;

    @Column(name = "channel_id", nullable = false, length = 32)
    private String channelId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "thumbnail_url", length = 512)
    private String thumbnailUrl;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "duration_seconds", precision = 8, scale = 2)
    private BigDecimal durationSeconds;

    /** Width and height of the video's true aspect ratio, as YouTube's embed sizes report it.
     * Null when YouTube didn't say; shown as 16:9 then. */
    @Column(name = "aspect_w")
    private Integer aspectW;

    @Column(name = "aspect_h")
    private Integer aspectH;

    @Column(name = "privacy_status", length = 12)
    private String privacyStatus;

    @Column(name = "embeddable")
    private Boolean embeddable;

    @Column(name = "age_restricted")
    private Boolean ageRestricted;

    @Column(name = "made_for_kids")
    private Boolean madeForKids;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    /** Set when a refresh no longer finds the video (deleted or made private). */
    @Column(name = "gone_at")
    private OffsetDateTime goneAt;
}
