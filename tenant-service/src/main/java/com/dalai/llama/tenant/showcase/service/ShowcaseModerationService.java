package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Brand reports, the ops kill switch, and profile suspension when billing lapses (rules 18 and
 * the "ops kill switch" in §21.6). Nothing here deletes anything: hiding is reversible. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowcaseModerationService {

    public enum ReportReason { RIGHTS, OFFENSIVE, MISLEADING, OTHER }

    private final ShowcaseItemRepository itemRepository;
    private final CreatorPublicProfileRepository profileRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Value("${showcase.reports.auto-hide-after:3}")
    private int autoHideAfter;

    /** One report per visitor per item; a repeat is ignored. Enough distinct reports hide the item
     * (hidden_by_ops) until ops reviews it. Unknown items are a 400 like any bad public input. */
    @Transactional
    public void report(String publicId, UUID visitorId, ReportReason reason, String note) {
        ShowcaseItem item = itemRepository.findByPublicId(publicId)
                .filter(i -> i.getStatus() == ShowcaseItemStatus.LIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown video"));
        int inserted;
        try {
            inserted = jdbc.update("""
                    INSERT INTO showcase_report (id, showcase_item_id, visitor_id, reason, note, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (showcase_item_id, visitor_id) DO NOTHING""",
                    UUID.randomUUID(), item.getId(), visitorId, reason.name(), trim(note), OffsetDateTime.now(clock));
        } catch (DataIntegrityViolationException e) {
            inserted = 0;
        }
        if (inserted == 0) return;
        item.setReportCount(item.getReportCount() + 1);
        if (!item.isHiddenByOps() && item.getReportCount() >= autoHideAfter) {
            item.setHiddenByOps(true);
            log.warn("Showcase item {} auto-hidden after {} reports", item.getPublicId(), item.getReportCount());
        }
        itemRepository.save(item);
    }

    @Transactional
    public ShowcaseItem setItemHidden(String publicId, boolean hidden) {
        ShowcaseItem item = itemRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("No item " + publicId));
        item.setHiddenByOps(hidden);
        return itemRepository.save(item);
    }

    @Transactional
    public CreatorPublicProfile setProfileStatus(String handle, ProfileStatus status) {
        CreatorPublicProfile profile = profileRepository.findByHandle(handle.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("No profile " + handle));
        profile.setStatus(status);
        return profileRepository.save(profile);
    }

    public List<ShowcaseItem> reported() {
        return itemRepository.findByReportCountGreaterThanOrderByReportCountDesc(0);
    }

    /** billing.state.changed: a lapsed subscription takes the profile down; a restored one brings it
     * back. A profile ops hid stays hidden either way. GRACE still counts as active. */
    @Transactional
    public void onBillingState(UUID tenantId, String billingState) {
        profileRepository.findById(tenantId).ifPresent(profile -> {
            boolean lapsed = "BLOCKED".equals(billingState) || "SUSPENDED".equals(billingState);
            if (lapsed && profile.getStatus() == ProfileStatus.ACTIVE) {
                profile.setStatus(ProfileStatus.SUSPENDED);
            } else if (!lapsed && profile.getStatus() == ProfileStatus.SUSPENDED) {
                profile.setStatus(ProfileStatus.ACTIVE);
            } else {
                return;
            }
            profileRepository.save(profile);
            log.info("Public profile tenant={} is now {} (billing {})", tenantId, profile.getStatus(), billingState);
        });
    }

    private static String trim(String note) {
        if (note == null || note.isBlank()) return null;
        String t = note.trim();
        return t.length() <= 500 ? t : t.substring(0, 500);
    }
}
