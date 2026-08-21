package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CritiqueSeverity;

/** Tolerant mapping from a critic's free-text severity onto the closed {@link CritiqueSeverity}
 * vocabulary -- defaults to P2 (advisory) rather than silently treating a malformed value as
 * either "definitely blocking" or "definitely fine". */
final class SeverityParser {

    private SeverityParser() {
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
