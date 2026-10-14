package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.CloneAudioService;
import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The recorded takes a shot's clip should carry, each placed where its line was planned to start.
 *
 * <p>A shot with several lines -- two people talking, or one person speaking twice around an
 * action -- is recorded one take per line, each in its own speaker's voice. Laying on only the
 * newest take dropped every other line, and laying it at 0s put it on the first frame. So every
 * line's current take is placed at its beat's start, and post-production mixes them into one track.
 *
 * <p>A take of the shot's whole line instead (one recording of everything said) starts at the
 * shot's first beat, and wins when it is newer than every per-line take: it is a re-record of the
 * same words. A take whose words no longer match its line (the line was rewritten since) is left
 * out -- it says something nobody approved. A shot with no beats starts its take at 0s.
 */
@Component
@RequiredArgsConstructor
public class ShotTakes {

    private final PreProductionServiceClient preProduction;
    private final CloneAudioService cloneAudio;

    /** One take placed in its shot. */
    public record TimedTake(UUID beatId, String audioUrl, double startSeconds, String text, Integer durationMs) {
    }

    public List<TimedTake> forShot(UUID tenantId, UUID projectId, UUID shotId) {
        List<PreProductionViews.ShotDialogueBeatView> beats = preProduction.getPrepareBundle(tenantId, projectId)
                .map(PreProductionViews.PrepareBundleView::shots).orElse(List.of()).stream()
                .filter(s -> s.shot() != null && shotId.equals(s.shot().id()))
                .findFirst()
                .map(PreProductionViews.ShotBundleView::dialogueBeats)
                .orElse(List.of());
        List<CloneAudioService.CloneAudioView> takes = cloneAudio.list(tenantId, projectId).stream()
                .filter(t -> shotId.equals(t.shotId()) && !t.rejected()).toList();
        return choose(beats == null ? List.of() : beats, takes);
    }

    static List<TimedTake> choose(List<PreProductionViews.ShotDialogueBeatView> beats,
                                  List<CloneAudioService.CloneAudioView> takes) {
        List<TimedTake> perLine = new ArrayList<>();
        OffsetDateTime newestPerLine = null;
        for (PreProductionViews.ShotDialogueBeatView beat : beats.stream()
                .sorted(Comparator.comparing(b -> b.startSeconds() == null ? BigDecimal.ZERO : b.startSeconds())).toList()) {
            if (beat.text() == null || beat.text().isBlank()) continue;
            Optional<CloneAudioService.CloneAudioView> take = newest(takes.stream()
                    .filter(t -> beat.id().equals(t.beatId()) && t.text() != null && t.text().trim().equals(beat.text().trim())).toList());
            if (take.isEmpty()) continue;
            perLine.add(timed(take.get(), start(beat.startSeconds())));
            if (newestPerLine == null || take.get().updatedAt().isAfter(newestPerLine)) newestPerLine = take.get().updatedAt();
        }
        List<UUID> beatIds = beats.stream().map(PreProductionViews.ShotDialogueBeatView::id).toList();
        Optional<CloneAudioService.CloneAudioView> wholeLine = newest(takes.stream()
                .filter(t -> t.beatId() == null || !beatIds.contains(t.beatId())).toList());
        boolean wholeLineIsNewer = wholeLine.isPresent()
                && (newestPerLine == null || wholeLine.get().updatedAt().isAfter(newestPerLine));
        if (wholeLineIsNewer) {
            double firstLine = beats.stream().map(PreProductionViews.ShotDialogueBeatView::startSeconds).filter(Objects::nonNull)
                    .mapToDouble(BigDecimal::doubleValue).min().orElse(0);
            return List.of(timed(wholeLine.get(), Math.max(0, firstLine)));
        }
        return perLine;
    }

    private static Optional<CloneAudioService.CloneAudioView> newest(List<CloneAudioService.CloneAudioView> takes) {
        return takes.stream().filter(t -> t.audioUrl() != null && t.updatedAt() != null)
                .max(Comparator.comparing(CloneAudioService.CloneAudioView::updatedAt));
    }

    private static double start(BigDecimal seconds) {
        return seconds == null ? 0 : Math.max(0, seconds.doubleValue());
    }

    private static TimedTake timed(CloneAudioService.CloneAudioView take, double startSeconds) {
        return new TimedTake(take.beatId(), take.audioUrl(), startSeconds, take.text(), take.durationMs());
    }
}
