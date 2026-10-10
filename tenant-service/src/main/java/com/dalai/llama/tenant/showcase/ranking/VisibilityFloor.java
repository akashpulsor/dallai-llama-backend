package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.RankingProperties;
import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** The self-adjusting bootstrap (rule 12). A surface's floor is the highest level whose creators,
 * together with everyone above them, supply at least {@code fillFactor x size} showable items. At
 * launch only L1 creators exist, so the floor is L1 and everyone shows; as funded platform films
 * accumulate the floor rises by itself, at most {@code maxFloorStepPerRun} levels per run. */
@Component
@RequiredArgsConstructor
public class VisibilityFloor {

    private final RankingProperties properties;

    /** @param itemsByLevel showable items of ready creators, keyed by their creator's level
     *  @param previous     the floor the last run chose, or null on the first run */
    public CreatorLevel floor(Map<CreatorLevel, Integer> itemsByLevel, int surfaceSize, CreatorLevel previous) {
        int needed = (int) Math.ceil(surfaceSize * properties.bootstrap().fillFactor());
        CreatorLevel target = CreatorLevel.L1;
        int atOrAbove = 0;
        for (CreatorLevel level = CreatorLevel.L4; level.atLeast(CreatorLevel.L1); level = level.previous()) {
            atOrAbove += itemsByLevel.getOrDefault(level, 0);
            if (atOrAbove >= needed) {
                target = level;
                break;
            }
        }
        if (previous == null || previous == CreatorLevel.L0) return target;
        int step = properties.bootstrap().maxFloorStepPerRun();
        int bounded = Math.max(previous.ordinal() - step, Math.min(previous.ordinal() + step, target.ordinal()));
        return CreatorLevel.values()[Math.max(CreatorLevel.L1.ordinal(), bounded)];
    }
}
