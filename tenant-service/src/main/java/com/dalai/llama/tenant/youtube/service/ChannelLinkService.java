package com.dalai.llama.tenant.youtube.service;

import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import com.dalai.llama.tenant.youtube.client.ChannelRef;
import com.dalai.llama.tenant.youtube.client.YouTubeDataClient;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.dto.ChannelLinkView;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Links a creator's YouTube channel without OAuth (CREATOR_SHOWCASE.md rule 2): we hand out a
 * code, the creator adds it to their channel description, and we read the description back with
 * our API key. Only the channel's owner can edit its description, so a match proves ownership. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelLinkService {

    private static final String CODE_PREFIX = "dalai-";
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I/L
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CreatorYouTubeChannelRepository channelRepository;
    private final CreatorProfileService profileService;
    private final YouTubeDataClient youTube;
    private final ChannelImportService importService;
    private final Clock clock;

    public Optional<ChannelLinkView> current(UUID tenantId) {
        return channelRepository.findById(tenantId).map(ChannelLinkService::toView);
    }

    /** Step 1: find the channel and issue a code. Re-running it for a channel that is still
     * pending keeps the same code, so a creator who already pasted it isn't sent back. */
    public ChannelLinkView start(UUID tenantId, String input) {
        profileService.requireProfile(tenantId);
        YouTubeDataClient.ChannelInfo channel = youTube.findChannel(ChannelRef.parse(input))
                .orElseThrow(() -> new IllegalArgumentException("We couldn't find that YouTube channel"));

        Optional<CreatorYouTubeChannel> mine = channelRepository.findById(tenantId);
        if (mine.isPresent() && mine.get().getStatus() == ChannelStatus.VERIFIED) {
            if (mine.get().getChannelId().equals(channel.channelId())) return toView(mine.get());
            throw new IllegalStateException("Your account already has a linked channel; contact support to change it");
        }
        releaseFromOtherPendingClaim(tenantId, channel.channelId());

        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean sameChannelPending = mine.isPresent() && mine.get().getChannelId().equals(channel.channelId());
        CreatorYouTubeChannel row = mine.orElseGet(() -> CreatorYouTubeChannel.builder().tenantId(tenantId).build());
        row.setChannelId(channel.channelId());
        row.setChannelTitle(channel.title());
        row.setChannelThumbnailUrl(channel.thumbnailUrl());
        row.setUploadsPlaylistId(channel.uploadsPlaylistId());
        if (!sameChannelPending) row.setVerificationCode(newCode());
        row.setStatus(ChannelStatus.PENDING);
        row.setFetchedAt(now);
        return toView(channelRepository.saveAndFlush(row));
    }

    /** Step 2: look for the code in the channel description. On a match the channel is linked and
     * its videos are imported; otherwise it stays pending and the creator can try again. */
    public ChannelLinkView verify(UUID tenantId) {
        CreatorYouTubeChannel row = channelRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Add your channel first"));
        if (row.getStatus() == ChannelStatus.VERIFIED) return toView(row);

        YouTubeDataClient.ChannelInfo channel = youTube.findChannel(new ChannelRef.ById(row.getChannelId()))
                .orElseThrow(() -> new IllegalArgumentException("That channel no longer exists on YouTube"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        row.setChannelTitle(channel.title());
        row.setChannelThumbnailUrl(channel.thumbnailUrl());
        row.setFetchedAt(now);
        if (!containsCode(channel.description(), row.getVerificationCode())) {
            channelRepository.save(row);
            throw new IllegalStateException("We couldn't find " + row.getVerificationCode()
                    + " in your channel description yet. YouTube can take a minute to update; try again shortly.");
        }
        row.setStatus(ChannelStatus.VERIFIED);
        row.setVerifiedAt(now);
        channelRepository.saveAndFlush(row);
        log.info("YouTube channel verified tenant={} channel={}", tenantId, row.getChannelId());

        importService.importAll(tenantId);
        return toView(channelRepository.findById(tenantId).orElse(row));
    }

    /** A pending claim proves nothing, so it must not block the real owner. Verified claims do
     * block: one channel belongs to one creator. */
    private void releaseFromOtherPendingClaim(UUID tenantId, String channelId) {
        channelRepository.findByChannelId(channelId)
                .filter(other -> !other.getTenantId().equals(tenantId))
                .ifPresent(other -> {
                    if (other.getStatus() == ChannelStatus.VERIFIED) {
                        throw new IllegalStateException("This channel is linked to another account; contact support");
                    }
                    channelRepository.delete(other);
                    channelRepository.flush();
                });
    }

    static boolean containsCode(String description, String code) {
        return description != null && description.toUpperCase(Locale.ROOT).contains(code.toUpperCase(Locale.ROOT));
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder(CODE_PREFIX);
        for (int i = 0; i < CODE_LENGTH; i++) code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    static ChannelLinkView toView(CreatorYouTubeChannel c) {
        return new ChannelLinkView(
                c.getStatus(),
                c.getChannelId(),
                c.getChannelTitle(),
                c.getChannelThumbnailUrl(),
                YouTubeLinks.channel(c.getChannelId()),
                c.getStatus() == ChannelStatus.PENDING ? c.getVerificationCode() : null,
                c.getVerifiedAt(),
                c.getLastSyncedAt());
    }
}
