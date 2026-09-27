package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.repository.DubJobRepository;
import com.dalai.llama.videogen.repository.ExportBundleRepository;
import com.dalai.llama.videogen.repository.FoleyCueRepository;
import com.dalai.llama.videogen.repository.ShotPromptReferenceRepository;
import com.dalai.llama.videogen.repository.ShotPromptRepository;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Drops everything this service holds for one shot, called when pre-production-service deletes
 * that shot.
 *
 * <p>Why it exists: the shot row lives in pre-production-service, but its prepared prompts,
 * dispatched jobs and rendered clips live here, in a different database. Deleting the shot
 * upstream left those behind as orphans -- invisible in the UI (which iterates live shots) but
 * still real rows that count toward batch progress, export bundles and any future query that
 * walks prompts rather than shots.
 *
 * <p>Two different keys, because the two halves of this service were written against different
 * ones: prompts and dub jobs key on {@code shotId}, while {@code VideoGenJob} keys on the shot's
 * string {@code shotRef}. The caller sends both; a null/blank shotRef simply skips that half
 * rather than guessing.
 *
 * <p>Best-effort by contract: the caller treats a failure here as non-fatal, because refusing to
 * delete a shot the creator asked to remove -- on the grounds that a downstream cleanup failed --
 * would be the wrong trade. An orphan row is recoverable; a shot the creator cannot delete is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShotArtifactCleanupService {

    private final ShotPromptRepository shotPromptRepository;
    private final ShotPromptReferenceRepository shotPromptReferenceRepository;
    private final FoleyCueRepository foleyCueRepository;
    private final ExportBundleRepository exportBundleRepository;
    private final DubJobRepository dubJobRepository;
    private final VideoGenJobRepository videoGenJobRepository;

    @Transactional
    public Summary deleteForShot(UUID tenantId, UUID shotId, String shotRef) {
        List<ShotPrompt> prompts = shotPromptRepository.findByShotIdOrderByCreatedAtDesc(shotId);
        List<UUID> promptIds = prompts.stream().map(ShotPrompt::getPromptId).toList();

        // Children first -- these reference prompt_id, and the FK would reject the parent delete.
        int references = 0, foley = 0, bundles = 0;
        if (!promptIds.isEmpty()) {
            references = shotPromptReferenceRepository.deleteByPromptIdIn(promptIds);
            foley = foleyCueRepository.deleteByPromptIdIn(promptIds);
            bundles = exportBundleRepository.deleteByPromptIdIn(promptIds);
        }
        shotPromptRepository.deleteAll(prompts);

        int dubs = dubJobRepository.deleteByShotId(shotId);
        int jobs = (shotRef == null || shotRef.isBlank())
                ? 0
                : videoGenJobRepository.deleteByTenantIdAndShotRef(tenantId, shotRef);

        Summary summary = new Summary(prompts.size(), references, foley, bundles, dubs, jobs);
        log.info("Cleaned video-gen artifacts for deleted shot shotId={} shotRef={} {}", shotId, shotRef, summary);
        return summary;
    }

    public record Summary(int prompts, int promptReferences, int foleyCues, int exportBundles,
                          int dubJobs, int videoGenJobs) {}
}
