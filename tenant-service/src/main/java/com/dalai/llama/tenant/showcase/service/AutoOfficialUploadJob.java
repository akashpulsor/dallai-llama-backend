package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uploads to Dalaillama's channel happen on their own, for every creator: each eligible film made
 * here (published, fully paid, client consent) and each live uploaded portfolio film is queued, and
 * OfficialUploadWorker uploads its stamped copy. Creators never ask for it. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoOfficialUploadJob {

    private final OfficialChannelAccess officialChannel;
    private final CreatorPublicProfileRepository profiles;
    private final PreProductionShowcaseClient preProduction;
    private final PlatformPublishService publishService;
    private final PortfolioOfficialUploadService portfolio;

    @Scheduled(fixedDelayString = "${video-host.official-channel.auto-queue-ms:3600000}", initialDelayString = "PT5M")
    public int run() {
        if (!officialChannel.ready()) return 0;
        int queued = 0;
        for (CreatorPublicProfile profile : profiles.findByStatus(ProfileStatus.ACTIVE)) {
            queued += queueProjectFilms(profile) + queuePortfolio(profile);
        }
        if (queued > 0) log.info("Queued {} film(s) for Dalaillama's channel", queued);
        return queued;
    }

    private int queueProjectFilms(CreatorPublicProfile profile) {
        int queued = 0;
        try {
            for (PreProductionShowcaseClient.FilmSummary film : preProduction.films(profile.getTenantId())) {
                // Cheap filter first: no consent, no upload (the full rules run in autoQueue).
                if (film.marketingTermsAcceptedAt() == null) continue;
                if (publishService.autoQueueOfficialUpload(profile, film.projectId())) queued++;
            }
        } catch (RuntimeException e) {
            log.warn("Auto official upload: skipped tenant {}'s films this round: {}", profile.getTenantId(), e.getMessage());
        }
        return queued;
    }

    private int queuePortfolio(CreatorPublicProfile profile) {
        try {
            return portfolio.queue(profile);
        } catch (RuntimeException e) {
            log.warn("Auto official upload: skipped tenant {}'s portfolio this round: {}", profile.getTenantId(), e.getMessage());
            return 0;
        }
    }
}
