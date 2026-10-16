package com.dalai.llama.videogen.dto;

import java.math.BigDecimal;

/** A film's shots as planned against as made, in the wallet's currency. */
public record GenerationSavingsView(
        int shots,
        /* Seconds of film the plan asked for. */
        int plannedSeconds,
        /* Seconds the models actually generated. */
        int generatedSeconds,
        /* Every shot at its planned length and its model's top resolution. */
        BigDecimal plannedCost,
        /* The clips as generated, plus slowing the short ones to their planned length. */
        BigDecimal actualCost,
        BigDecimal savedCost,
        int savedPercent,
        String currency
) {
}
