package com.dalai.llama.tenant.youtube.repository;

import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorYouTubeChannelRepository extends JpaRepository<CreatorYouTubeChannel, UUID> {

    Optional<CreatorYouTubeChannel> findByChannelId(String channelId);

    List<CreatorYouTubeChannel> findByFetchedAtBefore(OffsetDateTime cutoff);
}
