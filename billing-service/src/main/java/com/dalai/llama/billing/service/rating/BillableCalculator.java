package com.dalai.llama.billing.service.rating;

import org.springframework.stereotype.Component;

/**
 * Calculates billable units based on billing rules.
 * Handles minimum charges and billing increments.
 */
@Component
public class BillableCalculator {

    /**
     * Calculates billable seconds based on actual duration, billing increment, and minimum charge.
     *
     * @param actualSeconds The actual call duration in seconds
     * @param increment     The billing increment (e.g., 60 for per-minute billing)
     * @param minimum       The minimum billable seconds
     * @return The billable seconds, rounded up to the nearest increment
     *
     * Examples:
     * - 45 seconds, 60-second increment, 30-second minimum → 60 billable
     * - 25 seconds, 60-second increment, 30-second minimum → 60 billable (minimum applied first)
     * - 70 seconds, 60-second increment, 30-second minimum → 120 billable
     * - 0 seconds, 60-second increment, 0-second minimum → 0 billable
     */
    public int calculate(int actualSeconds, int increment, int minimum) {
        // Handle edge case of zero duration
        if (actualSeconds == 0 && minimum == 0) {
            return 0;
        }

        // Apply minimum charge first
        int effective = Math.max(actualSeconds, minimum);

        // Handle edge case of zero increment
        if (increment <= 0) {
            increment = 1;
        }

        // Round up to the nearest increment
        return (int) Math.ceil((double) effective / increment) * increment;
    }

    /**
     * Calculates billable tokens for AI usage.
     * Token billing is typically per-token with no increments.
     */
    public int calculateTokens(int actualTokens, int minimum) {
        return Math.max(actualTokens, minimum);
    }
}
