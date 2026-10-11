package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.dto.PickVideoRequest;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import com.dalai.llama.tenant.youtube.service.VideoEligibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bootstrapping (rule 45): a creator's profile fills itself from their verified channel, so the
 * showcase has films from day one instead of filter tags over empty pages. Eligible videos are added
 * up to the external-pick limit, ones the creator marked as AI-made on YouTube first, newest next.
 * A video the creator removed (or hid) is never added back; they stay in control from the profile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoShowcaseService {

    /** At or under this many seconds, or vertical: a short. */
    static final int SHORT_SECONDS = 60;

    private final CreatorYouTubeChannelRepository channels;
    private final CreatorPublicProfileRepository profiles;
    private final YouTubeVideoRepository videos;
    private final ShowcaseItemRepository items;
    private final ShowcasePickService picks;
    private final VideoEligibility eligibility;
    private final ShowcaseProperties properties;

    /** Adds eligible channel videos to the profile; returns how many were added. */
    public int fill(UUID tenantId) {
        Optional<CreatorYouTubeChannel> channel = channels.findById(tenantId).filter(c -> c.getStatus() == ChannelStatus.VERIFIED);
        Optional<CreatorPublicProfile> profile = profiles.findById(tenantId);
        if (channel.isEmpty() || profile.isEmpty()) return 0;
        long room = properties.picks().maxExternalPicks()
                - items.countByTenantIdAndOriginAndStatus(tenantId, ShowcaseOrigin.EXTERNAL, ShowcaseItemStatus.LIVE);
        if (room <= 0) return 0;
        ShowcaseIndustry industry = profile.get().getIndustries().stream().findFirst().orElse(ShowcaseIndustry.OTHER);
        List<YouTubeVideo> candidates = videos.findByChannelIdAndGoneAtIsNullOrderByPublishedAtDesc(channel.get().getChannelId()).stream()
                .filter(v -> eligibility.problem(v).isEmpty())
                // Any item for the video -- live, hidden or removed -- means the creator already decided.
                .filter(v -> items.findByYoutubeVideoId(v.getVideoId()).isEmpty())
                .sorted(Comparator.comparing((YouTubeVideo v) -> !Boolean.TRUE.equals(v.getContainsSyntheticMedia())))
                .limit(room)
                .toList();
        int added = 0;
        for (YouTubeVideo v : candidates) {
            try {
                picks.pick(tenantId, new PickVideoRequest(v.getVideoId(), industry, format(v), null, null, true));
                added++;
            } catch (RuntimeException e) {
                log.warn("Auto showcase: skipped video {} for tenant {}: {}", v.getVideoId(), tenantId, e.getMessage());
            }
        }
        if (added > 0) log.info("Auto showcase: added {} video(s) to tenant {}'s profile", added, tenantId);
        return added;
    }

    /** Catches every verified channel up (new uploads, newly connected channels). DB work only. */
    @Scheduled(fixedDelayString = "${showcase.auto-fill-ms:900000}", initialDelayString = "PT2M")
    public int fillAll() {
        int added = 0;
        for (CreatorYouTubeChannel c : channels.findAll()) {
            if (c.getStatus() != ChannelStatus.VERIFIED) continue;
            try {
                added += fill(c.getTenantId());
            } catch (RuntimeException e) {
                log.warn("Auto showcase: tenant {} skipped this round: {}", c.getTenantId(), e.getMessage());
            }
        }
        return added;
    }

    static ShowcaseFormat format(YouTubeVideo v) {
        boolean vertical = v.getAspectW() != null && v.getAspectH() != null && v.getAspectH() > v.getAspectW();
        boolean short_ = v.getDurationSeconds() != null && v.getDurationSeconds().intValue() <= SHORT_SECONDS;
        return vertical || short_ ? ShowcaseFormat.SOCIAL_SHORT : ShowcaseFormat.BRAND_FILM;
    }
}
