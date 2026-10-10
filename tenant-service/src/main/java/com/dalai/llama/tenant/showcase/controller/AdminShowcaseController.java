package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseRankingRun;
import com.dalai.llama.tenant.showcase.ranking.RankingRunJob;
import com.dalai.llama.tenant.showcase.service.ShowcaseModerationService;
import com.dalai.llama.tenant.showcase.service.VideoHostHealth;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Ops tools for the showcase: re-score after a config change, the kill switch for an item or a
 * profile, reported items, and the video-host health panel. Mesh-internal under
 * {@code /api/v1/internal/admin/**}; the ops dashboard reaches it through its own pass-through,
 * never the public gateway. */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/showcase")
public class AdminShowcaseController {

    private final RankingRunJob rankingRun;
    private final ShowcaseModerationService moderation;
    private final VideoHostHealth health;

    public record RankingRunView(OffsetDateTime runAt, BigDecimal maturity, CreatorLevel landingFloor,
                                 CreatorLevel topFloor, CreatorLevel autoFloor, String configVersion,
                                 int creatorsRanked, int itemsScored) {
        static RankingRunView of(ShowcaseRankingRun r) {
            return new RankingRunView(r.getRunAt(), r.getMaturity(), r.getLandingFloor(), r.getTopFloor(),
                    r.getAutoFloor(), r.getConfigVersion(), r.getCreatorsRanked(), r.getItemsScored());
        }
    }

    public record ItemModerationView(String publicId, String youtubeVideoId, int reportCount, boolean hiddenByOps) {
        static ItemModerationView of(ShowcaseItem i) {
            return new ItemModerationView(i.getPublicId(), i.getYoutubeVideoId(), i.getReportCount(), i.isHiddenByOps());
        }
    }

    public record HideRequest(@NotNull Boolean hidden) {
    }

    public record ProfileStatusRequest(@NotNull ProfileStatus status) {
    }

    @PostMapping("/rescore")
    public RankingRunView rescore() {
        return RankingRunView.of(rankingRun.run());
    }

    @PatchMapping("/items/{publicId}")
    public ItemModerationView hideItem(@PathVariable String publicId, @Valid @RequestBody HideRequest request) {
        return ItemModerationView.of(moderation.setItemHidden(publicId, request.hidden()));
    }

    @PatchMapping("/profiles/{handle}")
    public ProfileStatus setProfileStatus(@PathVariable String handle, @Valid @RequestBody ProfileStatusRequest request) {
        return moderation.setProfileStatus(handle, request.status()).getStatus();
    }

    @GetMapping("/reports")
    public List<ItemModerationView> reported() {
        return moderation.reported().stream().map(ItemModerationView::of).toList();
    }

    @GetMapping("/health")
    public VideoHostHealth.HealthView health() {
        return health.view();
    }

    @PostMapping("/health/probe")
    public VideoHostHealth.Probe probe() {
        return health.probe();
    }
}
