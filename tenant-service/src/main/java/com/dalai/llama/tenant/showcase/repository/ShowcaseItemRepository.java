package com.dalai.llama.tenant.showcase.repository;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowcaseItemRepository extends JpaRepository<ShowcaseItem, UUID> {

    Optional<ShowcaseItem> findByPublicId(String publicId);

    Optional<ShowcaseItem> findByYoutubeVideoId(String youtubeVideoId);

    Optional<ShowcaseItem> findBySourceProjectId(UUID sourceProjectId);

    List<ShowcaseItem> findByTenantIdAndStatusInOrderBySortOrderAscPublishedAtDesc(
            UUID tenantId, Collection<ShowcaseItemStatus> statuses);

    long countByTenantIdAndOriginAndStatus(UUID tenantId, ShowcaseOrigin origin, ShowcaseItemStatus status);

    long countByTenantIdAndStatus(UUID tenantId, ShowcaseItemStatus status);

    /** Item-and-video half of "may be shown publicly", for an item aliased {@code i} and its
     * video aliased {@code v}: live, not hidden by ops, and its YouTube video still served, showable
     * and fetched recently enough for YouTube's 30-day rule. With {@code :platformSelf} (the SELF
     * host fallback, rule 5) platform films play from our own copy, so YouTube's state no longer
     * matters for them. */
    String ITEM_SHOWABLE = """
            i.status = com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus.LIVE
            AND i.hiddenByOps = false
            AND ((:platformSelf = true AND i.origin = com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin.PLATFORM)
                 OR (v.goneAt IS NULL
                     AND v.fetchedAt > :freshAfter
                     AND v.privacyStatus = 'public'
                     AND v.embeddable = true
                     AND v.ageRestricted = false
                     AND v.madeForKids = false))
            """;

    /** The same rule for the aliases {@code x} / {@code xv} used inside the readiness subquery. */
    String OTHER_ITEM_SHOWABLE = """
            x.status = com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus.LIVE
            AND x.hiddenByOps = false
            AND ((:platformSelf = true AND x.origin = com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin.PLATFORM)
                 OR (xv.goneAt IS NULL
                     AND xv.fetchedAt > :freshAfter
                     AND xv.privacyStatus = 'public'
                     AND xv.embeddable = true
                     AND xv.ageRestricted = false
                     AND xv.madeForKids = false))
            """;

    /** The one definition of "may be shown publicly", shared by the profile page and the feed:
     * the item is showable, its profile is active, its channel verified, and the creator has at
     * least {@code :minItems} showable items (a profile isn't shown half-built). */
    String PUBLIC_VISIBLE = ITEM_SHOWABLE + """
            AND p.status = com.dalai.llama.tenant.showcase.domain.ProfileStatus.ACTIVE
            AND EXISTS (SELECT 1 FROM CreatorYouTubeChannel c
                        WHERE c.tenantId = i.tenantId
                          AND c.status = com.dalai.llama.tenant.youtube.domain.ChannelStatus.VERIFIED)
            AND (SELECT COUNT(x) FROM ShowcaseItem x, YouTubeVideo xv
                 WHERE xv.videoId = x.youtubeVideoId AND x.tenantId = i.tenantId AND
            """ + OTHER_ITEM_SHOWABLE + """
                ) >= :minItems
            """;

    @Query("SELECT i FROM ShowcaseItem i, CreatorPublicProfile p, YouTubeVideo v"
            + " WHERE p.tenantId = i.tenantId AND v.videoId = i.youtubeVideoId AND " + PUBLIC_VISIBLE
            + " AND (:industry IS NULL OR i.industry = :industry)"
            + " AND (:format IS NULL OR i.format = :format)"
            + " ORDER BY i.publishedAt DESC, i.id")
    List<ShowcaseItem> findPublicFeed(@Param("industry") ShowcaseIndustry industry,
                                      @Param("format") ShowcaseFormat format,
                                      @Param("freshAfter") OffsetDateTime freshAfter,
                                      @Param("minItems") long minItems,
                                      @Param("platformSelf") boolean platformSelf,
                                      Pageable page);

    @Query("SELECT i FROM ShowcaseItem i, CreatorPublicProfile p, YouTubeVideo v"
            + " WHERE p.tenantId = i.tenantId AND v.videoId = i.youtubeVideoId AND i.tenantId = :tenantId AND "
            + PUBLIC_VISIBLE
            + " ORDER BY i.sortOrder, i.publishedAt DESC")
    List<ShowcaseItem> findPublicForCreator(@Param("tenantId") UUID tenantId,
                                            @Param("freshAfter") OffsetDateTime freshAfter,
                                            @Param("minItems") long minItems,
                                            @Param("platformSelf") boolean platformSelf);

    /** Every item that passes the item-and-video half of the public rules, for the ranking run. */
    @Query("SELECT i FROM ShowcaseItem i, YouTubeVideo v WHERE v.videoId = i.youtubeVideoId AND " + ITEM_SHOWABLE)
    List<ShowcaseItem> findAllShowable(@Param("freshAfter") OffsetDateTime freshAfter,
                                       @Param("platformSelf") boolean platformSelf);

    List<ShowcaseItem> findByStatus(ShowcaseItemStatus status);

    List<ShowcaseItem> findByReportCountGreaterThanOrderByReportCountDesc(int reportCount);

    /** Public items ranked by score, limited to creators at the given levels (a surface's floor and
     * above). Items not yet scored come last. */
    @Query("SELECT i FROM ShowcaseItem i, CreatorPublicProfile p, YouTubeVideo v"
            + " WHERE p.tenantId = i.tenantId AND v.videoId = i.youtubeVideoId AND " + PUBLIC_VISIBLE
            + " AND p.level IN :levels"
            + " AND (:industry IS NULL OR i.industry = :industry)"
            + " AND (:format IS NULL OR i.format = :format)"
            + " ORDER BY i.globalScore DESC NULLS LAST, i.publishedAt DESC, i.id")
    List<ShowcaseItem> findPublicByScore(@Param("industry") ShowcaseIndustry industry,
                                         @Param("format") ShowcaseFormat format,
                                         @Param("freshAfter") OffsetDateTime freshAfter,
                                         @Param("minItems") long minItems,
                                         @Param("platformSelf") boolean platformSelf,
                                         @Param("levels") Collection<com.dalai.llama.tenant.showcase.domain.CreatorLevel> levels,
                                         Pageable page);

    @Modifying
    @Query("UPDATE ShowcaseItem i SET i.playCount = i.playCount + 1 WHERE i.id = :id")
    void incrementPlays(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE ShowcaseItem i SET i.fullPlayCount = i.fullPlayCount + 1 WHERE i.id = :id")
    void incrementFullPlays(@Param("id") UUID id);
}
