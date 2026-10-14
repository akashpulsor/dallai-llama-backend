package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.postproduction.PostProductionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * What happens to a clip's sound and length after the render, decided in one place.
 *
 * <p>The video model never voices a line. Every clip is generated silent, and a shot with a line gets
 * the take the creator recorded -- the same take the dialogue fit was measured against, never a voice
 * the model invents or a fresh synthesis at render. Removing the voice from a clip is a step taken
 * after generation.
 *
 * <p>A clip at the shot's length or longer is handed straight to post-production, which trims it to
 * the shot and lays the take on. A shorter clip is left as rendered: slowing it to the shot, always
 * with frame interpolation, is the creator's step after generation, and it lays the take on too.
 * Music is never laid on a clip: shot music is a sound layer, mixed into the film when it renders.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClipFinishing {

    private final VideoGenJobRepository jobs;
    private final PostProductionClient postProduction;

    /**
     * How many seconds of the generated clip one second of the shot takes. Below 1 when a shorter
     * clip is to be slowed to the shot's length (a 3s clip for a 7s shot: 3/7); 1 otherwise -- a clip
     * that plays as rendered, or is trimmed. Decided here, beside how a clip is finished, so planning
     * reads it rather than knowing how finishing works.
     */
    public static BigDecimal clipSecondsPerShotSecond(Integer generationSeconds, BigDecimal plannedSeconds) {
        if (generationSeconds == null || plannedSeconds == null || plannedSeconds.signum() <= 0
                || BigDecimal.valueOf(generationSeconds).compareTo(plannedSeconds) >= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(generationSeconds).divide(plannedSeconds, 6, RoundingMode.HALF_UP);
    }

    /**
     * Post-production finishes this clip without being asked: it is at least the shot's length (so
     * nothing is slowed) and is either longer -- to be trimmed -- or has a line to lay on.
     */
    public static boolean finishesAutomatically(Integer generationSeconds, Integer plannedSeconds, boolean hasLine) {
        if (generationSeconds == null || plannedSeconds == null || generationSeconds < plannedSeconds) {
            return false;
        }
        return generationSeconds > plannedSeconds || hasLine;
    }

    /**
     * Asks post-production to trim the finished clip to the shot and lay the recorded take on.
     * Returns the request id, or null when the clip needs nothing or the request could not be made;
     * the clip itself stands either way.
     */
    public UUID requestFinish(VideoGenJob job, ShotPrompt prompt, boolean hasLine) {
        if (prompt.getShotId() == null
                || !finishesAutomatically(job.getDurationSeconds(), job.getPlannedDurationSeconds(), hasLine)) {
            return null;
        }
        try {
            UUID requestId = postProduction.requestConform(job.getTenantId(), job.getProjectId(), prompt.getShotId(),
                    job.getPlannedDurationSeconds(), true);
            job.setConformRequestId(requestId);
            jobs.save(job);
            log.info("Finishing jobId={} at the shot's {}s from a {}s clip hasLine={} requestId={}", job.getJobId(),
                    job.getPlannedDurationSeconds(), job.getDurationSeconds(), hasLine, requestId);
            return requestId;
        } catch (VideoGenException ex) {
            log.warn("Could not ask post-production to finish jobId={}: {}", job.getJobId(), ex.getMessage());
            return null;
        }
    }
}
