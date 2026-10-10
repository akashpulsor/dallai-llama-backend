package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Rule 8: a project is "verified funded" when the client locked it (which only happens once they
 * paid in full) and the app shows the real work behind it. Thresholds come from config. */
@Component
@RequiredArgsConstructor
public class FundingVerifier {

    private final ShowcaseProperties properties;

    public boolean verified(ShowcaseSource source) {
        ShowcaseProperties.Funding rules = properties.funding();
        if (rules.requireClientLocked() && source.clientLockedAt() == null) return false;
        return source.shotImages() >= rules.minShotImages()
                && source.clientReviewSessions() >= rules.minClientReviewSessions()
                && source.clientComments() + source.clientApprovals() >= rules.minClientCommentsOrApprovals();
    }
}
