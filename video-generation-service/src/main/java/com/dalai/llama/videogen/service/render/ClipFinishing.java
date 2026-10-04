package com.dalai.llama.videogen.service.render;

import com.dalai.llama.videogen.domain.entity.ShotPrompt;
import com.dalai.llama.videogen.domain.entity.VideoGenJob;
import com.dalai.llama.videogen.dto.generationplan.GenerationControlsView;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.repository.VideoGenJobRepository;
import com.dalai.llama.videogen.service.BackgroundMusicMixService;
import com.dalai.llama.videogen.service.BeatDubbingService;
import com.dalai.llama.videogen.service.DispatchResult;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.postproduction.PostProductionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything that happens to a clip's sound and length around the render, decided in one place.
 *
 * <p>One question settles most of it: will post-production conform this clip to the shot's planned
 * length? It does whenever the clip was generated at a different length -- shorter to save money,
 * or longer because the model has a minimum -- and the "conform" control is on. Such a clip is
 * generated silent (slowed-down speech is unusable), and the dub and the music bed are not laid on
 * here, because conform lays both on at the right length. Otherwise the clip is finished here, as
 * it always was. Nothing in here fails a render: dub, music and the conform request are all
 * best-effort extras on a clip that already exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClipFinishing {

    private final BeatDubbingService dubbing;
    private final BackgroundMusicMixService music;
    private final VideoGenJobRepository jobs;
    private final PostProductionClient postProduction;

    public record Finished(String outputUri, BigDecimal cost) {
    }

    /** Post-production will bring this clip to its planned length. */
    public static boolean conformsLater(Integer generationSeconds, Integer plannedSeconds, GenerationControlsView controls) {
        return controls.conformToPlannedDuration() && plannedSeconds != null && generationSeconds != null
                && !Objects.equals(generationSeconds, plannedSeconds);
    }

    /** Whether the model is told to generate no audio of its own. */
    public boolean muteAudio(ShotContext shot, Integer generationSeconds, Integer plannedSeconds, GenerationControlsView controls) {
        boolean hasSomethingToSay = (shot.dialogueBeats() != null && !shot.dialogueBeats().isEmpty())
                || (shot.narrative() != null && shot.narrative().dialogue() != null && !shot.narrative().dialogue().isBlank());
        boolean dubbedHere = controls.autoDubDialogue() && dubbing.canAutoDub(shot.dialogueBeats());
        return !hasSomethingToSay || dubbedHere || conformsLater(generationSeconds, plannedSeconds, controls);
    }

    /** The rendered clip with its dub and music, unless post-production will lay those on instead. */
    public Finished finish(VideoGenJob job, ShotPrompt prompt, List<DialogueBeat> beats, DispatchResult result,
                           GenerationControlsView controls) {
        String uri = result.outputUri();
        BigDecimal cost = result.actualCost();
        if (conformsLater(job.getDurationSeconds(), job.getPlannedDurationSeconds(), controls)) {
            return new Finished(uri, cost);
        }
        if (job.isMuteAudio() && controls.autoDubDialogue() && dubbing.canAutoDub(beats)) {
            Finished dubbed = dub(job, beats, uri, cost);
            uri = dubbed.outputUri();
            cost = dubbed.cost();
        }
        if (controls.mixBackgroundMusic()) {
            uri = music.mixIfPresent(job.getTenantId(), job.getJobId(), prompt.getPromptId(), prompt.getShotId(), uri);
        }
        return new Finished(uri, cost);
    }

    /**
     * Asks post-production to conform the finished clip to the planned length -- slowed (with frame
     * interpolation when that control is on) or trimmed, with the line and music laid on. Returns the
     * request id, or null when the clip needs no conform or the request could not be made; the clip
     * itself stands either way.
     */
    public UUID requestConform(VideoGenJob job, ShotPrompt prompt, GenerationControlsView controls) {
        if (prompt.getShotId() == null || !conformsLater(job.getDurationSeconds(), job.getPlannedDurationSeconds(), controls)) {
            return null;
        }
        try {
            UUID requestId = postProduction.requestConform(job.getTenantId(), job.getProjectId(), prompt.getShotId(),
                    job.getPlannedDurationSeconds(), controls.interpolateWhenSlowing());
            job.setConformRequestId(requestId);
            jobs.save(job);
            log.info("Conforming jobId={} from {}s to the planned {}s interpolate={} requestId={}", job.getJobId(),
                    job.getDurationSeconds(), job.getPlannedDurationSeconds(), controls.interpolateWhenSlowing(), requestId);
            return requestId;
        } catch (VideoGenException ex) {
            log.warn("Could not ask post-production to conform jobId={}: {}", job.getJobId(), ex.getMessage());
            return null;
        }
    }

    /** A failed dub keeps the silent clip: losing a paid render over its voice track is the wrong trade. */
    private Finished dub(VideoGenJob job, List<DialogueBeat> beats, String uri, BigDecimal cost) {
        Finished finished;
        try {
            BeatDubbingService.DubResult dub = dubbing.dub(job.getTenantId().toString(), job.getJobId(), job.getProjectId(),
                    beats, uri, job.getVoiceCloneModel(), job.getTtsModel(), job.getDurationSeconds(), job.getFps());
            finished = new Finished(dub.finalVideoUrl(), cost.add(dub.cost()));
            job.setDubSucceeded(true);
        } catch (RuntimeException ex) {
            log.warn("Auto-dub failed jobId={} -- keeping the silent clip: {}", job.getJobId(), ex.getMessage());
            finished = new Finished(uri, cost);
            job.setDubSucceeded(false);
        }
        jobs.save(job);
        return finished;
    }
}
