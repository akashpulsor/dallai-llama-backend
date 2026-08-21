package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.CritiqueSeverity;

/** Tolerant mapping from a marketing-plan critic's free-text severity onto the closed {@link
 * CritiqueSeverity} vocabulary -- defaults to P2 rather than treating a malformed value as
 * either "definitely blocking" or "definitely fine". Same logic as {@code SeverityParser} in the
 * shot-critique package, copied rather than shared since it's package-private there. */
final class MarketingPlanSeverityParser {

    private MarketingPlanSeverityParser() {
    }

    static CritiqueSeverity parse(String raw) {
        if (raw == null) {
            return CritiqueSeverity.P2;
        }
        String normalized = raw.trim().toUpperCase();
        for (CritiqueSeverity severity : CritiqueSeverity.values()) {
            if (severity.name().equals(normalized)) {
                return severity;
            }
        }
        return CritiqueSeverity.P2;
    }
}
