package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which job the video page is told each shot is on.
 *
 * <p>This used to group by shot_ref, which moves: adding, deleting or regenerating shots
 * renumbers it, while a job row keeps the string it was created with. So an old FAILED job could
 * carry a shot_ref that now names a different shot, and that shot's card showed a failed render it
 * never had. shot_id is assigned once and never moves, which is why it is the key.
 */
class LatestJobPerShotTest {

    private static VideoGenJob job(UUID jobId, String shotRef, JobStatus status, String outputKey) {
        return VideoGenJob.builder()
                .jobId(jobId).shotRef(shotRef).status(status).outputObjectKey(outputKey).build();
    }

    @Test
    void anOldFailedJobWhoseShotRefNowNamesAnotherShotStaysOnItsOwnShot() {
        UUID shotOne = UUID.randomUUID();
        UUID shotTwo = UUID.randomUUID();
        UUID failedJob = UUID.randomUUID();
        UUID pendingJob = UUID.randomUUID();

        // Both rows say "shot-01-002": the failed one because it was created before a renumber,
        // the pending one because that is shot 2's ref today.
        VideoGenJob failedOnShotOne = job(failedJob, "shot-01-002", JobStatus.FAILED, null);
        VideoGenJob pendingOnShotTwo = job(pendingJob, "shot-01-002", JobStatus.PENDING_APPROVAL, null);

        List<VideoGenJob> result = ShotGenerationOrchestrator.latestJobPerShot(
                List.of(pendingOnShotTwo, failedOnShotOne),
                Map.of(failedJob, shotOne, pendingJob, shotTwo));

        // Two shots, two jobs. Grouping by shot_ref returned one and hid the other.
        assertThat(result).containsExactly(pendingOnShotTwo, failedOnShotOne);
    }

    @Test
    void aFinishedRenderBeatsANewerRowWithNoVideo() {
        UUID shot = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID finished = UUID.randomUUID();
        VideoGenJob newerNoVideo = job(newer, "shot-01-001", JobStatus.FAILED, null);
        VideoGenJob completed = job(finished, "shot-01-001", JobStatus.COMPLETED, "clips/one.mp4");

        List<VideoGenJob> result = ShotGenerationOrchestrator.latestJobPerShot(
                List.of(newerNoVideo, completed), Map.of(newer, shot, finished, shot));

        assertThat(result).containsExactly(completed);
    }

    @Test
    void theNewestJobWinsWhenNeitherHasRendered() {
        UUID shot = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        VideoGenJob pending = job(newest, "shot-01-001", JobStatus.PENDING_APPROVAL, null);
        VideoGenJob failed = job(older, "shot-01-001", JobStatus.FAILED, null);

        // A shot re-prepared after a failure: the fresh PENDING job is what the card must see,
        // otherwise it goes on reporting a failure the creator has already acted on.
        List<VideoGenJob> result = ShotGenerationOrchestrator.latestJobPerShot(
                List.of(pending, failed), Map.of(newest, shot, older, shot));

        assertThat(result).containsExactly(pending);
    }

    @Test
    void jobsNoPromptPointsAtFallBackToShotRefWithoutCollapsingTogether() {
        VideoGenJob one = job(UUID.randomUUID(), "shot-01-001", JobStatus.PENDING_APPROVAL, null);
        VideoGenJob two = job(UUID.randomUUID(), "shot-01-002", JobStatus.PENDING_APPROVAL, null);

        List<VideoGenJob> result = ShotGenerationOrchestrator.latestJobPerShot(List.of(one, two), Map.of());

        assertThat(result).containsExactly(one, two);
    }
}
