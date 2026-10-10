package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.client.PreProductionShowcaseClient.ShowcaseSource;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FundingVerifierTest {

    private static final OffsetDateTime LOCKED = OffsetDateTime.parse("2026-10-01T10:00:00Z");

    private final FundingVerifier verifier = new FundingVerifier(new ShowcaseProperties("https://dalaillama.in", 30,
            new ShowcaseProperties.Profile(30, 90, 3, List.of()),
            new ShowcaseProperties.Picks(2, 6),
            new ShowcaseProperties.Funding(true, 5, 1, 1),
            new ShowcaseProperties.PlatformMatch(1.5, "Made on Dalaillama")));

    @Test
    void aLockedProjectWithRealWorkIsVerified() {
        assertThat(verifier.verified(source(LOCKED, 5, 1, 1, 0))).isTrue();
        assertThat(verifier.verified(source(LOCKED, 9, 2, 0, 1))).isTrue();
    }

    @Test
    void eachMissingPieceFailsIt() {
        assertThat(verifier.verified(source(null, 9, 2, 3, 1))).as("not locked = not paid").isFalse();
        assertThat(verifier.verified(source(LOCKED, 4, 2, 3, 1))).as("too few generated images").isFalse();
        assertThat(verifier.verified(source(LOCKED, 9, 0, 3, 1))).as("no client review").isFalse();
        assertThat(verifier.verified(source(LOCKED, 9, 2, 0, 0))).as("no client feedback").isFalse();
    }

    private static ShowcaseSource source(OffsetDateTime locked, long images, long sessions, long comments, long approvals) {
        return new ShowcaseSource(UUID.randomUUID(), "Film", "CLIENT_LOCKED", true, LOCKED.minusDays(1),
                new BigDecimal("30"), 1080, 1920, locked, null, null, images, sessions, comments, approvals, null);
    }
}
