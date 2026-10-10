package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcasePlayStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Plays and full plays on our own pages: our own data, which the ranking may use (YouTube's own
 * counts may not be). Counted once per visitor per item per day. */
@Service
@RequiredArgsConstructor
public class ShowcaseEngagementService {

    private final ShowcaseItemRepository itemRepository;
    private final ShowcasePlayStore playStore;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Clock clock;

    public record LikeState(boolean liked, int likeCount) {
    }

    /** Anonymous likes, keyed by the browser's visitor id; repeats are no-ops. Our own signal for
     * ranking, never YouTube's like count. */
    @Transactional
    public LikeState setLiked(String publicId, UUID visitorId, boolean liked) {
        ShowcaseItem item = itemRepository.findByPublicId(publicId)
                .filter(i -> i.getStatus() == ShowcaseItemStatus.LIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown video"));
        if (liked) {
            if (jdbc.update("INSERT INTO showcase_like (showcase_item_id, visitor_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    item.getId(), visitorId) == 1) {
                jdbc.update("UPDATE showcase_item SET like_count = like_count + 1 WHERE id = ?", item.getId());
            }
        } else if (jdbc.update("DELETE FROM showcase_like WHERE showcase_item_id = ? AND visitor_id = ?", item.getId(), visitorId) == 1) {
            jdbc.update("UPDATE showcase_item SET like_count = GREATEST(like_count - 1, 0) WHERE id = ?", item.getId());
        }
        Integer count = jdbc.queryForObject("SELECT like_count FROM showcase_item WHERE id = ?", Integer.class, item.getId());
        return new LikeState(liked, count == null ? 0 : count);
    }

    @Transactional
    public void recordPlay(String publicId, UUID visitorId, boolean completed) {
        ShowcaseItem item = itemRepository.findByPublicId(publicId)
                .filter(i -> i.getStatus() == ShowcaseItemStatus.LIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown video"));
        LocalDate today = LocalDate.now(clock);
        if (playStore.recordPlay(item.getId(), visitorId, today)) itemRepository.incrementPlays(item.getId());
        if (completed && playStore.recordFullPlay(item.getId(), visitorId, today)) itemRepository.incrementFullPlays(item.getId());
    }
}
