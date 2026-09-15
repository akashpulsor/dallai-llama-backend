package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.dto.DialogueFitView;
import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Answers "will this line fit this shot?" for a project, before any money is spent.
 *
 * <p>The failure this exists for is audio being CUT. Measured across the live project, every one of
 * the eight synthesized takes runs longer than the shot planned for it -- 4.32s of narration in a 3s
 * clip, 12.2s in a 5s clip -- and not one falls short. The line is then severed: the model speaks
 * what fits and stops, or the mux pins the dubbed track to the video length and drops the rest,
 * reporting success either way because it did what it was told.
 *
 * <p>The opposite case is also reported and is deliberately not treated as a fault. A short line in a
 * long shot plays in full; the shot simply runs on, which is ordinary filmmaking. It appears only
 * because clips are billed by the second and the spare seconds are worth offering back.
 *
 * <p>Judged against MEASURED audio wherever it exists, and against this project's own measured
 * speaking rate where it does not -- see {@link SpeakingRate} for why neither a model's opinion nor a
 * fixed characters-per-second constant is good enough.
 *
 * <p>Read-only and deliberately cheap: one query for the project's synthesized takes and one fetch
 * of the prepare bundle, both of which the prepare path already does. No LLM call, no probe on the
 * happy path, nothing persisted. It can therefore be polled by the page and called again after
 * every remedy without thinking about cost, which matters because the whole point is to let a
 * creator try a change and see what it did.
 *
 * <p>It never repairs anything. Extending a shot costs more per second, rephrasing changes someone's
 * writing, and going with the original accepts a clipped tail -- three different prices, and which
 * one is worth paying is a directorial judgement. So this reports, and the page offers the choice.
 */
@Slf4j
@Service
public class DialogueFitReportService {

    private final PreProductionServiceClient preProductionClient;
    private final DialogueAudioIndex dialogueAudioIndex;
    private final DialogueRetimeService dialogueRetimeService;
    private final DialogueFitAdvisorService dialogueFitAdvisorService;
    private final double tailSeconds;
    private final int minShotSeconds;
    private final int maxShotSeconds;
    private final double estimatedCharsPerSecond;
    private final double maxExtensionSeconds;

    public DialogueFitReportService(
            PreProductionServiceClient preProductionClient,
            DialogueAudioIndex dialogueAudioIndex,
            DialogueRetimeService dialogueRetimeService,
            DialogueFitAdvisorService dialogueFitAdvisorService,
            @Value("${video-gen.dialogue-fit.tail-seconds:0.4}") double tailSeconds,
            @Value("${video-gen.min-shot-duration-seconds:3}") int minShotSeconds,
            @Value("${video-gen.max-shot-duration-seconds:10}") int maxShotSeconds,
            @Value("${video-gen.dialogue-fit.estimated-chars-per-second:14}") double estimatedCharsPerSecond,
            @Value("${video-gen.dialogue-fit.max-extension-seconds:2}") double maxExtensionSeconds
    ) {
        this.preProductionClient = preProductionClient;
        this.dialogueAudioIndex = dialogueAudioIndex;
        this.dialogueRetimeService = dialogueRetimeService;
        this.dialogueFitAdvisorService = dialogueFitAdvisorService;
        this.tailSeconds = tailSeconds;
        this.minShotSeconds = minShotSeconds;
        this.maxShotSeconds = maxShotSeconds;
        this.estimatedCharsPerSecond = estimatedCharsPerSecond;
        this.maxExtensionSeconds = maxExtensionSeconds;
    }

    /** Every shot in the project with anything spoken in it. Shots with no dialogue are left out
     * rather than listed as fitting: a list where most rows say "nothing to check" buries the few
     * that need a decision. */
    public List<DialogueFitView> reportProject(UUID tenantId, UUID projectId) {
        PreProductionViews.PrepareBundleView bundle = bundle(tenantId, projectId);
        DialogueAudioIndex.Index measured = dialogueAudioIndex.forProject(tenantId, projectId);
        List<DialogueFitView> views = new ArrayList<>();
        for (PreProductionViews.ShotBundleView shotBundle : safeShots(bundle)) {
            if (shotBundle.shot() == null) {
                continue;
            }
            DialogueFitView view = report(shotBundle, measured);
            if (!"NO_DIALOGUE".equals(view.verdict()) || !view.beats().isEmpty()) {
                views.add(view);
            }
        }
        views.sort(Comparator.comparing(v -> v.shotNumber() == null ? Integer.MAX_VALUE : v.shotNumber()));
        return views;
    }

    /** One shot -- what the page calls after a remedy to see whether it landed. */
    public DialogueFitView reportShot(UUID tenantId, UUID projectId, UUID shotId) {
        PreProductionViews.PrepareBundleView bundle = bundle(tenantId, projectId);
        PreProductionViews.ShotBundleView shotBundle = safeShots(bundle).stream()
                .filter(sb -> sb.shot() != null && shotId.equals(sb.shot().id()))
                .findFirst()
                .orElseThrow(() -> VideoGenException.notFound("No shot " + shotId + " in project " + projectId));
        return report(shotBundle, dialogueAudioIndex.forProject(tenantId, projectId));
    }

    /**
     * Rewrites one line to a target length, sized from how fast this voice really speaks.
     *
     * <p>Lives here rather than on the controller because the numbers that make the rewrite work are
     * the same ones this class already resolves: the line's own measured take where it has one, the
     * project's measured rate otherwise. Handing the model a target without a measured current
     * length is what made the old prompt useless.
     */
    public DialogueRetimeService.Retimed retime(UUID tenantId, UUID projectId, UUID shotId, UUID beatId,
                                                String dialogue, double targetSeconds, String languageCode) {
        DialogueAudioIndex.Index measured = dialogueAudioIndex.forProject(tenantId, projectId);
        // Best available rate, in the order SpeakingRate documents: this exact line, then the
        // project's other takes, then the configured guess.
        SpeakingRate rate = beatId != null ? measured.rateForBeat(beatId) : null;
        if (rate == null && shotId != null) {
            rate = measured.rateForShot(shotId);
        }
        if (rate == null) {
            rate = measured.speakingRate(estimatedCharsPerSecond);
        }
        java.math.BigDecimal current = beatId != null
                ? measured.measuredForBeat(beatId)
                : measured.measuredForShot(shotId);
        double currentSeconds = current != null ? current.doubleValue() : rate.secondsFor(dialogue);
        return dialogueRetimeService.retime(tenantId, projectId, dialogue, targetSeconds, currentSeconds,
                rate, languageCode);
    }

    /**
     * What this shot should do about its overrun, judged rather than calculated.
     *
     * <p>Separate from {@link #reportShot} and called only when that reported a problem, which is
     * what keeps a model out of the path of every shot that is simply fine. The report is arithmetic
     * against measured audio and costs nothing; this costs a prompt call, so it runs when there is
     * actually a decision to make.
     *
     * <p>Null when there is nothing to advise on, when advice is switched off, or when the gateway
     * could not answer -- in all three cases the creator still has the three options, just without a
     * suggested one.
     */
    public DialogueFitAdvisorService.Advice advise(UUID tenantId, UUID projectId, UUID shotId) {
        PreProductionViews.PrepareBundleView bundle = bundle(tenantId, projectId);
        PreProductionViews.ShotBundleView shotBundle = safeShots(bundle).stream()
                .filter(sb -> sb.shot() != null && shotId.equals(sb.shot().id()))
                .findFirst()
                .orElseThrow(() -> VideoGenException.notFound("No shot " + shotId + " in project " + projectId));
        PreProductionViews.ShotView shot = shotBundle.shot();
        DialogueFitMath.Report fit = evaluate(shotBundle, dialogueAudioIndex.forProject(tenantId, projectId));
        if (fit == null || !fit.verdict().needsAttention()) {
            return null;
        }
        // The ceiling the recommendation is clamped to is the hard one, not the cost allowance: the
        // whole point of asking is that this shot might be worth more seconds than a blanket rule
        // would give it. What it costs is in the prompt, so the judgement is made knowing the price.
        return dialogueFitAdvisorService.advise(tenantId, projectId, shot.shotRef(), shot.shotType(),
                shot.action(), spokenLine(shot), fit, maxShotSeconds);
    }

    /** The spans a shot's dialogue occupies, and the verdict on them. Shared by the report the
     * creator reads and by the advice call, so the two can never disagree about what is wrong. */
    private DialogueFitMath.Report evaluate(PreProductionViews.ShotBundleView shotBundle,
                                            DialogueAudioIndex.Index measured) {
        return build(shotBundle, measured).report();
    }

    private DialogueFitView report(PreProductionViews.ShotBundleView shotBundle, DialogueAudioIndex.Index measured) {
        Built built = build(shotBundle, measured);
        DialogueFitMath.Report report = built.report();
        PreProductionViews.ShotView shot = shotBundle.shot();
        List<DialogueFitView.BeatFitView> beatViews = built.beatViews();
        return toView(shot, report, beatViews);
    }

    private record Built(DialogueFitMath.Report report, List<DialogueFitView.BeatFitView> beatViews) {}

    private Built build(PreProductionViews.ShotBundleView shotBundle, DialogueAudioIndex.Index measured) {
        SpeakingRate rate = measured.speakingRate(estimatedCharsPerSecond);
        PreProductionViews.ShotView shot = shotBundle.shot();
        List<PreProductionViews.ShotDialogueBeatView> beats = shotBundle.dialogueBeats() == null
                ? List.of() : shotBundle.dialogueBeats();

        List<DialogueFitMath.BeatSpan> spans = new ArrayList<>();
        List<DialogueFitView.BeatFitView> beatViews = new ArrayList<>();
        if (beats.isEmpty()) {
            // A shot with a voice-over but no beats broken out: the whole line is one span starting
            // at zero. Still worth checking -- this is the shape most short-form shots have.
            String line = spokenLine(shot);
            if (line != null) {
                BigDecimal shotMeasured = measured.measuredForShot(shot.id());
                double spoken = shotMeasured != null ? shotMeasured.doubleValue() : rate.secondsFor(line);
                spans.add(new DialogueFitMath.BeatSpan(0, 0, spoken, shotMeasured != null));
                beatViews.add(new DialogueFitView.BeatFitView(null, 0, shot.primaryCharacterKey(), line,
                        0, null, round(spoken), shotMeasured != null));
            }
        } else {
            int index = 0;
            for (PreProductionViews.ShotDialogueBeatView beat : beats.stream()
                    .sorted(Comparator.comparing(b -> b.startSeconds() == null ? BigDecimal.ZERO : b.startSeconds()))
                    .toList()) {
                double start = beat.startSeconds() == null ? 0 : beat.startSeconds().doubleValue();
                BigDecimal beatMeasured = measured.measuredForBeat(beat.id());
                boolean isMeasured = beatMeasured != null;
                double spoken = isMeasured
                        ? beatMeasured.doubleValue()
                        : rate.secondsFor(beat.text());
                int orderIndex = beat.orderIndex() == null ? index : beat.orderIndex();
                spans.add(new DialogueFitMath.BeatSpan(orderIndex, start, spoken, isMeasured));
                beatViews.add(new DialogueFitView.BeatFitView(beat.id(), orderIndex, beat.characterKey(),
                        beat.text(), round(start),
                        beat.durationSeconds() == null ? null : round(beat.durationSeconds().doubleValue()),
                        round(spoken), isMeasured));
                index++;
            }
        }

        return new Built(DialogueFitMath.evaluate(
                shot.durationSeconds(), shot.fps(), spans, tailSeconds, minShotSeconds, maxShotSeconds,
                maxExtensionSeconds), beatViews);
    }

    private DialogueFitView toView(PreProductionViews.ShotView shot, DialogueFitMath.Report report,
                                   List<DialogueFitView.BeatFitView> beatViews) {
        return new DialogueFitView(
                shot.id(),
                shot.shotRef(),
                shot.shotNumber(),
                report.verdict().name(),
                report.plannedDurationSeconds(),
                report.fps(),
                report.fpsAssumed(),
                round(report.audioSpanSeconds()),
                report.tailSeconds(),
                round(report.requiredSeconds()),
                report.slackFrames(),
                round(report.slackSeconds()),
                report.allowedDurationSeconds(),
                report.suggestedDurationSeconds(),
                report.suggestedTargetAudioSeconds() == null ? null : round(report.suggestedTargetAudioSeconds()),
                report.measured(),
                spokenLine(shot),
                beatViews,
                report.overlaps().stream()
                        .map(o -> new DialogueFitView.OverlapView(
                                o.earlierOrderIndex(), o.laterOrderIndex(), o.overlapFrames()))
                        .toList());
    }

    /** What is actually said in this shot. {@code scriptLine} counts only for DIALOGUE shots -- for
     * an ACTION or B_ROLL shot it is scene direction, not something anybody speaks, and treating it
     * as dialogue would report a fit problem on a shot with no dialogue in it. Same rule the video
     * page already applies when deciding whether a shot has a voice to clone. */
    private String spokenLine(PreProductionViews.ShotView shot) {
        String voiceOver = shot.voiceOver();
        if (voiceOver != null && !voiceOver.isBlank()) {
            return voiceOver.trim();
        }
        if ("DIALOGUE".equals(shot.shotType()) && shot.scriptLine() != null && !shot.scriptLine().isBlank()) {
            return shot.scriptLine().trim();
        }
        return null;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private PreProductionViews.PrepareBundleView bundle(UUID tenantId, UUID projectId) {
        return preProductionClient.getPrepareBundle(tenantId, projectId)
                .orElseThrow(() -> VideoGenException.upstream(
                        "pre-production-service returned no prepare bundle for project " + projectId));
    }

    private static List<PreProductionViews.ShotBundleView> safeShots(PreProductionViews.PrepareBundleView bundle) {
        return bundle.shots() == null ? List.of() : bundle.shots();
    }
}
