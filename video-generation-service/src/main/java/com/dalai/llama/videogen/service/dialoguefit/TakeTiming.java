package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.preproduction.PreProductionServiceClient;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a recorded take starts in its shot -- the moment the line was planned to be spoken.
 *
 * <p>A take laid on at 0s puts the voice on the first frame, so a shot planned as "she looks up,
 * then speaks at 1.2s" has the line arrive while she is still looking up, and every word after it
 * early. The render-time dub used to place the track at its first beat's start; a recorded take
 * laid on afterwards needs the same.
 *
 * <p>A take recorded for one beat starts where that beat does. A take of the shot's whole line
 * starts at its earliest beat. A shot with no beats starts at 0s.
 */
@Component
@RequiredArgsConstructor
public class TakeTiming {

    private final PreProductionServiceClient preProduction;

    public double startSeconds(UUID tenantId, UUID projectId, UUID shotId, UUID beatId) {
        List<PreProductionViews.ShotDialogueBeatView> beats = preProduction.getPrepareBundle(tenantId, projectId)
                .map(PreProductionViews.PrepareBundleView::shots).orElse(List.of()).stream()
                .filter(s -> s.shot() != null && shotId.equals(s.shot().id()))
                .findFirst()
                .map(PreProductionViews.ShotBundleView::dialogueBeats)
                .orElse(List.of());
        return startSeconds(beats == null ? List.of() : beats, beatId);
    }

    static double startSeconds(List<PreProductionViews.ShotDialogueBeatView> beats, UUID beatId) {
        if (beatId != null) {
            BigDecimal own = beats.stream().filter(b -> beatId.equals(b.id())).map(PreProductionViews.ShotDialogueBeatView::startSeconds)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (own != null) return Math.max(0, own.doubleValue());
        }
        return beats.stream().map(PreProductionViews.ShotDialogueBeatView::startSeconds).filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue).min().stream().map(s -> Math.max(0, s)).findFirst().orElse(0);
    }
}
