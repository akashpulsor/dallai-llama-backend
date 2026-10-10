package com.dalai.llama.tenant.youtube.repository;

import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface YouTubeVideoRepository extends JpaRepository<YouTubeVideo, String> {

    List<YouTubeVideo> findByChannelIdAndGoneAtIsNullOrderByPublishedAtDesc(String channelId);

    /** Oldest cached rows first, for the 30-day refresh. */
    List<YouTubeVideo> findByGoneAtIsNullAndFetchedAtBeforeOrderByFetchedAtAsc(OffsetDateTime cutoff, Pageable page);
}
