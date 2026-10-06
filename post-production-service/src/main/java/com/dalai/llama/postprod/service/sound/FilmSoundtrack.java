package com.dalai.llama.postprod.service.sound;

import com.dalai.llama.postprod.domain.entity.SoundLayer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where each sound layer falls in the finished film. A layer is anchored to its shot, so its film
 * time is the shot's start (the length of every shot before it, in film order) plus its own offset.
 * Pure: the assembler supplies the shots in order with their real cut lengths.
 */
public final class FilmSoundtrack {

    private FilmSoundtrack() {}

    /** A shot as it sits in the film. */
    public record ShotSpan(UUID shotId, BigDecimal durationSeconds) {}

    /** A layer and the moment it starts in the film. */
    public record Placement(SoundLayer layer, long startMs) {}

    /** Every included layer whose shot is in the film and that starts before the film ends, in
     * film order. Layers of shots no longer in the film, or placed past its end, are left out. */
    public static List<Placement> place(List<ShotSpan> shotsInOrder, List<SoundLayer> layers) {
        Map<UUID, Long> shotStartMs = new HashMap<>();
        long clock = 0;
        for (ShotSpan shot : shotsInOrder) {
            shotStartMs.put(shot.shotId(), clock);
            clock += toMs(shot.durationSeconds());
        }
        long filmEndMs = clock;
        List<Placement> placements = new ArrayList<>();
        for (SoundLayer layer : layers) {
            if (!layer.isIncluded()) continue;
            Long shotStart = shotStartMs.get(layer.getShotId());
            if (shotStart == null) continue;
            long start = shotStart + Math.max(0, layer.getOffsetMs());
            if (start >= filmEndMs) continue;
            placements.add(new Placement(layer, start));
        }
        placements.sort(java.util.Comparator.comparingLong(Placement::startMs));
        return placements;
    }

    private static long toMs(BigDecimal seconds) {
        return seconds == null ? 0 : seconds.movePointRight(3).longValue();
    }
}
