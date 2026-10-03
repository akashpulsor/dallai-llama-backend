package com.dalai.llama.billing.service;

import java.math.BigDecimal;

/**
 * A quoted project's price as the customer sees it: video production (itself scripting and
 * screenplay, shot planning, frame generation and video generation) plus music production, which
 * together make the quoted total. The same lines back the creator's and the ops view of a project,
 * so what a client was shown and what the creator reads back can never disagree.
 */
public record ProductionCharges(
        BigDecimal scriptingAndScreenplay,
        BigDecimal shotPlanning,
        BigDecimal frameGeneration,
        BigDecimal videoGeneration,
        BigDecimal videoProduction,
        BigDecimal musicProduction,
        BigDecimal total,
        String currency
) {
}
