package com.dalai.llama.tenant.showcase.ranking;

import com.dalai.llama.tenant.showcase.config.RankingProperties;

import java.util.List;
import java.util.Map;

/** The production defaults from application.yml, for pure tests. */
public final class TestRanking {

    private TestRanking() {
    }

    public static RankingProperties defaults() {
        return new RankingProperties("v1",
                new RankingProperties.Ladder(1, 1, 90, 3, 2, Map.of("L1", 0.6, "L2", 0.8, "L3", 1.0, "L4", 1.1), 60, 0.5),
                new RankingProperties.Bootstrap(40, 0.3, 1.5, 1),
                new RankingProperties.Scoring(0.30, 0.20, 0.15, 0.25, 0.10, 20, 0.05, 0.30, 3, 21),
                new RankingProperties.Reinforcement(72, 2, 3, 14, 50),
                new RankingProperties.Landing(12, 6, 2, 4, List.of(), 24, 9));
    }
}
