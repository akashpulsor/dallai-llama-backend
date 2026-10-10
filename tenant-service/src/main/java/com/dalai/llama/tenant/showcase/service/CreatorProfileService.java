package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ProfileChecklistItem;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorHandleHistory;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.dto.HandleAvailabilityView;
import com.dalai.llama.tenant.showcase.dto.MyPublicProfileView;
import com.dalai.llama.tenant.showcase.dto.UpdatePublicProfileRequest;
import com.dalai.llama.tenant.showcase.repository.CreatorHandleHistoryRepository;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;
import com.dalai.llama.tenant.youtube.repository.CreatorYouTubeChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Creates and edits creator public profiles. Owns handle availability, which needs the
 * database; {@link HandlePolicy} owns the pure format rules. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorProfileService {

    static final String TAKEN = "TAKEN";
    private static final int MAX_GENERATED_CANDIDATES = 50;
    private static final String DEFAULT_DISPLAY_NAME = "Creator";

    private final CreatorPublicProfileRepository profileRepository;
    private final CreatorHandleHistoryRepository handleHistoryRepository;
    private final CreatorYouTubeChannelRepository channelRepository;
    private final ShowcaseItemRepository itemRepository;
    private final HandlePolicy handlePolicy;
    private final TenantRepository tenantRepository;
    private final ShowcaseProperties properties;
    private final Clock clock;

    /** Creates the creator's profile with a handle generated from their name, or returns the
     * existing one. Safe to call again for the same tenant (Kafka redelivery, backfill). */
    public CreatorPublicProfile ensureProfile(UUID tenantId, String name) {
        Optional<CreatorPublicProfile> existing = profileRepository.findById(tenantId);
        if (existing.isPresent()) return existing.get();

        String displayName = name == null || name.isBlank() ? DEFAULT_DISPLAY_NAME : truncate(name.trim(), 80);
        String handle = firstFreeHandle(handlePolicy.baseFromName(name), tenantId);
        CreatorPublicProfile profile = CreatorPublicProfile.builder()
                .tenantId(tenantId)
                .handle(handle)
                .displayName(displayName)
                .status(ProfileStatus.ACTIVE)
                .build();
        try {
            CreatorPublicProfile saved = profileRepository.saveAndFlush(profile);
            log.info("Created public profile tenant={} handle={}", tenantId, handle);
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Either a concurrent activation created this tenant's profile, or another tenant took
            // the same handle between our check and insert. The first case resolves by re-reading;
            // the second by trying the next free handle once.
            return profileRepository.findById(tenantId).orElseGet(() -> {
                profile.setHandle(firstFreeHandle(handle, tenantId));
                return profileRepository.saveAndFlush(profile);
            });
        }
    }

    public Optional<MyPublicProfileView> findMine(UUID tenantId) {
        return ensureProfile(tenantId).map(this::toView);
    }

    /** The creator's profile, created on first use for any live tenant (bootstrapping: creators
     * who never had a subscription event still get one). Empty for unknown, deleted or suspended
     * tenants. */
    public Optional<CreatorPublicProfile> ensureProfile(UUID tenantId) {
        Optional<CreatorPublicProfile> existing = profileRepository.findById(tenantId);
        if (existing.isPresent()) return existing;
        return tenantRepository.findById(tenantId)
                .filter(t -> t.getStatus() != TenantStatus.DELETED && t.getStatus() != TenantStatus.SUSPENDED)
                .map(t -> ensureProfile(tenantId, t.getName()));
    }

    /** {@link #ensureProfile(UUID)} or a 409 with a plain message. */
    public CreatorPublicProfile requireProfile(UUID tenantId) {
        return ensureProfile(tenantId).orElseThrow(() -> new IllegalStateException("Your account can't have a public profile right now"));
    }

    @Transactional
    public MyPublicProfileView update(UUID tenantId, UpdatePublicProfileRequest request) {
        CreatorPublicProfile profile = profileRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("No public profile for this account yet"));
        if (request.industries().size() > properties.profile().maxIndustries()) {
            throw new IllegalArgumentException("Choose at most " + properties.profile().maxIndustries() + " industries");
        }
        if (!request.handle().equals(profile.getHandle())) {
            changeHandle(profile, request.handle());
        }
        profile.setDisplayName(request.displayName().trim());
        profile.setHeadline(blankToNull(request.headline()));
        profile.setBio(blankToNull(request.bio()));
        profile.setCountryCode(request.countryCode() == null ? null : request.countryCode().toUpperCase(Locale.ROOT));
        profile.setWebsiteUrl(blankToNull(request.websiteUrl()));
        profile.getIndustries().clear();
        profile.getIndustries().addAll(request.industries());
        profile.setAutoPicksEnabled(request.autoPicksEnabled());
        return toView(profileRepository.save(profile));
    }

    /** Live check for the handle field. The raw input is normalised first, so the UI can show the
     * creator what their handle will actually be. */
    public HandleAvailabilityView checkHandle(UUID tenantId, String raw) {
        String handle = handlePolicy.normalize(raw);
        return unavailableReason(handle, tenantId)
                .map(reason -> new HandleAvailabilityView(handle, false, reason))
                .orElseGet(() -> new HandleAvailabilityView(handle, true, null));
    }

    private void changeHandle(CreatorPublicProfile profile, String requested) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime allowedFrom = handleChangeAllowedFrom(profile);
        if (allowedFrom != null && now.isBefore(allowedFrom)) {
            throw new IllegalStateException("You can change your handle again from " + allowedFrom.toLocalDate());
        }
        unavailableReason(requested, profile.getTenantId()).ifPresent(reason -> {
            throw new IllegalArgumentException("Handle '" + requested + "' can't be used: " + reason);
        });
        handleHistoryRepository.deleteById(requested); // reclaiming one of the creator's own old handles
        handleHistoryRepository.save(new CreatorHandleHistory(profile.getHandle(), profile.getTenantId(), now));
        profile.setHandle(requested);
        profile.setHandleChangedAt(now);
    }

    private String firstFreeHandle(String base, UUID tenantId) {
        for (int n = 1; n <= MAX_GENERATED_CANDIDATES; n++) {
            String candidate = handlePolicy.candidate(base, n);
            if (unavailableReason(candidate, tenantId).isEmpty()) return candidate;
        }
        return handlePolicy.normalize(base + "-" + UUID.randomUUID().toString().substring(0, 6));
    }

    /** Empty when {@code handle} is valid and free for this tenant. */
    private Optional<String> unavailableReason(String handle, UUID tenantId) {
        Optional<HandlePolicy.Problem> problem = handlePolicy.problem(handle);
        if (problem.isPresent()) return Optional.of(problem.get().name());

        boolean heldByOther = profileRepository.findByHandle(handle)
                .filter(p -> !p.getTenantId().equals(tenantId))
                .isPresent();
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(properties.profile().oldHandleRedirectDays());
        boolean reservedForOther = handleHistoryRepository.findById(handle)
                .filter(h -> h.getReleasedAt().isAfter(cutoff) && !h.getTenantId().equals(tenantId))
                .isPresent();
        return heldByOther || reservedForOther ? Optional.of(TAKEN) : Optional.empty();
    }

    private OffsetDateTime handleChangeAllowedFrom(CreatorPublicProfile profile) {
        if (profile.getHandleChangedAt() == null) return null;
        OffsetDateTime from = profile.getHandleChangedAt().plusDays(properties.profile().handleChangeCooldownDays());
        return from.isAfter(OffsetDateTime.now(clock)) ? from : null;
    }

    private MyPublicProfileView toView(CreatorPublicProfile p) {
        CreatorYouTubeChannel channel = channelRepository.findById(p.getTenantId()).orElse(null);
        String avatar = CreatorAvatar.of(p, channel);
        long livePicks = itemRepository.countByTenantIdAndStatus(p.getTenantId(), ShowcaseItemStatus.LIVE);
        return new MyPublicProfileView(
                p.getHandle(),
                properties.publicBaseUrl() + "/c/" + p.getHandle(),
                p.getDisplayName(),
                p.getHeadline(),
                p.getBio(),
                avatar,
                p.getCountryCode(),
                p.getWebsiteUrl(),
                p.getIndustries().isEmpty() ? EnumSet.noneOf(ShowcaseIndustry.class) : EnumSet.copyOf(p.getIndustries()),
                p.isAutoPicksEnabled(),
                p.getStatus(),
                handleChangeAllowedFrom(p),
                missing(p, channel != null && channel.getStatus() == ChannelStatus.VERIFIED, avatar, livePicks));
    }

    private List<ProfileChecklistItem> missing(CreatorPublicProfile p, boolean channelVerified, String avatar, long livePicks) {
        List<ProfileChecklistItem> missing = new ArrayList<>();
        if (!channelVerified) missing.add(ProfileChecklistItem.YOUTUBE_CHANNEL);
        if (livePicks < properties.picks().minInitialPicks()) missing.add(ProfileChecklistItem.SHOWCASE_PICKS);
        if (avatar == null) missing.add(ProfileChecklistItem.AVATAR);
        if (p.getHeadline() == null) missing.add(ProfileChecklistItem.HEADLINE);
        if (p.getIndustries().isEmpty()) missing.add(ProfileChecklistItem.INDUSTRY);
        return missing;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
