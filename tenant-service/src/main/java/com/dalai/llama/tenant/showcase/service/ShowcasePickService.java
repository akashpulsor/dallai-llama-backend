package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PickVideoRequest;
import com.dalai.llama.tenant.showcase.dto.UpdateShowcaseItemRequest;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import com.dalai.llama.tenant.youtube.service.VideoEligibility;
import com.dalai.llama.tenant.youtube.service.YouTubeLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The creator managing the videos on their own profile: pick from their channel, edit, hide,
 * remove, reorder. PLATFORM items are added by the publish flow, not here. */
@Service
@RequiredArgsConstructor
public class ShowcasePickService {

    private static final EnumSet<ShowcaseItemStatus> MANAGED = EnumSet.of(ShowcaseItemStatus.LIVE, ShowcaseItemStatus.HIDDEN);

    private final ShowcaseItemRepository itemRepository;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final YouTubeVideoRepository videoRepository;
    private final VideoEligibility eligibility;
    private final PublicIdGenerator publicIds;
    private final ShowcaseProperties properties;
    private final Clock clock;

    @Transactional
    public MyShowcaseItemView pick(UUID tenantId, PickVideoRequest request) {
        CreatorYouTubeChannel channel = channelRepository.findById(tenantId)
                .filter(c -> c.getStatus() == ChannelStatus.VERIFIED)
                .orElseThrow(() -> new IllegalStateException("Verify your YouTube channel first"));
        YouTubeVideo video = videoRepository.findById(request.youtubeVideoId())
                .filter(v -> v.getChannelId().equals(channel.getChannelId()))
                .orElseThrow(() -> new IllegalArgumentException("That video isn't on your linked channel; try syncing it"));
        eligibility.problem(video).ifPresent(reason -> {
            throw new IllegalArgumentException("This video can't be showcased: " + reason);
        });

        ShowcaseItem item = itemRepository.findByYoutubeVideoId(video.getVideoId()).orElse(null);
        if (item != null && item.getStatus() != ShowcaseItemStatus.REMOVED) {
            throw new IllegalStateException("This video is already on your profile");
        }
        ensureRoomForAnotherExternal(tenantId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (item == null) {
            item = ShowcaseItem.builder()
                    .id(UUID.randomUUID())
                    .publicId(publicIds.next())
                    .tenantId(tenantId)
                    .youtubeVideoId(video.getVideoId())
                    .origin(ShowcaseOrigin.EXTERNAL)
                    .publishedAt(now)
                    .build();
        }
        item.setIndustry(request.industry());
        item.setFormat(request.format());
        item.setClientLabel(blankToNull(request.clientLabel()));
        item.setTitleOverride(blankToNull(request.titleOverride()));
        item.setRightsConfirmedAt(now);
        item.setStatus(ShowcaseItemStatus.LIVE);
        item.setSortOrder((int) itemRepository.countByTenantIdAndStatus(tenantId, ShowcaseItemStatus.LIVE)
                + (int) itemRepository.countByTenantIdAndStatus(tenantId, ShowcaseItemStatus.HIDDEN));
        return toView(itemRepository.save(item), video);
    }

    public List<MyShowcaseItemView> listMine(UUID tenantId) {
        List<ShowcaseItem> items = itemRepository.findByTenantIdAndStatusInOrderBySortOrderAscPublishedAtDesc(tenantId, MANAGED);
        Map<String, YouTubeVideo> videos = videoRepository.findAllById(items.stream().map(ShowcaseItem::getYoutubeVideoId).toList())
                .stream().collect(Collectors.toMap(YouTubeVideo::getVideoId, Function.identity()));
        return items.stream().map(i -> toView(i, videos.get(i.getYoutubeVideoId()))).toList();
    }

    @Transactional
    public MyShowcaseItemView update(UUID tenantId, UUID itemId, UpdateShowcaseItemRequest request) {
        ShowcaseItem item = owned(tenantId, itemId);
        boolean showing = item.getStatus() == ShowcaseItemStatus.LIVE;
        if (request.visible() && !showing && item.getOrigin() == ShowcaseOrigin.EXTERNAL) ensureRoomForAnotherExternal(tenantId);
        item.setIndustry(request.industry());
        item.setFormat(request.format());
        item.setClientLabel(blankToNull(request.clientLabel()));
        item.setTitleOverride(blankToNull(request.titleOverride()));
        item.setStatus(request.visible() ? ShowcaseItemStatus.LIVE : ShowcaseItemStatus.HIDDEN);
        return toView(itemRepository.save(item), videoRepository.findById(item.getYoutubeVideoId()).orElse(null));
    }

    @Transactional
    public void remove(UUID tenantId, UUID itemId) {
        ShowcaseItem item = owned(tenantId, itemId);
        item.setStatus(ShowcaseItemStatus.REMOVED);
        itemRepository.save(item);
    }

    @Transactional
    public List<MyShowcaseItemView> reorder(UUID tenantId, List<UUID> itemIds) {
        List<ShowcaseItem> items = itemRepository.findByTenantIdAndStatusInOrderBySortOrderAscPublishedAtDesc(tenantId, MANAGED);
        if (items.size() != itemIds.size() || !new HashSet<>(itemIds).equals(items.stream().map(ShowcaseItem::getId).collect(Collectors.toSet()))) {
            throw new IllegalArgumentException("Send every item on your profile exactly once");
        }
        Map<UUID, ShowcaseItem> byId = items.stream().collect(Collectors.toMap(ShowcaseItem::getId, Function.identity()));
        for (int i = 0; i < itemIds.size(); i++) byId.get(itemIds.get(i)).setSortOrder(i);
        itemRepository.saveAll(items);
        return listMine(tenantId);
    }

    private void ensureRoomForAnotherExternal(UUID tenantId) {
        long live = itemRepository.countByTenantIdAndOriginAndStatus(tenantId, ShowcaseOrigin.EXTERNAL, ShowcaseItemStatus.LIVE);
        if (live >= properties.picks().maxExternalPicks()) {
            throw new IllegalStateException("You can show up to " + properties.picks().maxExternalPicks()
                    + " videos from your channel; hide one first");
        }
    }

    private ShowcaseItem owned(UUID tenantId, UUID itemId) {
        return itemRepository.findById(itemId)
                .filter(i -> i.getTenantId().equals(tenantId) && i.getStatus() != ShowcaseItemStatus.REMOVED)
                .orElseThrow(() -> new IllegalArgumentException("No such item on your profile"));
    }

    static MyShowcaseItemView toView(ShowcaseItem i, YouTubeVideo v) {
        VideoShape shape = VideoShape.of(v);
        return new MyShowcaseItemView(
                i.getId(),
                i.getPublicId(),
                i.getYoutubeVideoId(),
                i.getTitleOverride() != null ? i.getTitleOverride() : v == null ? null : v.getTitle(),
                v == null ? null : v.getThumbnailUrl(),
                shape.width(),
                shape.height(),
                i.getOrigin(),
                i.getIndustry(),
                i.getFormat(),
                i.getClientLabel(),
                i.getTitleOverride(),
                i.getStatus(),
                i.isHiddenByOps(),
                i.getPlayCount(),
                i.getFullPlayCount(),
                YouTubeLinks.watch(i.getYoutubeVideoId(), shape.vertical(), v == null ? null : v.getDurationSeconds()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
