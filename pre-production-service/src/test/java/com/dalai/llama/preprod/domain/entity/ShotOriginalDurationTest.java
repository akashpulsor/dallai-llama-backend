package com.dalai.llama.preprod.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The shot list's planned length survives the creator changing the shot's length. */
class ShotOriginalDurationTest {

    @Test
    void aNewShotRemembersTheLengthItWasPlannedAt() {
        Shot shot = Shot.builder().durationSeconds(5).build();

        shot.rememberOriginalDuration();
        shot.setDurationSeconds(7);
        shot.rememberOriginalDuration();

        assertThat(shot.getOriginalDurationSeconds()).isEqualTo(5);
        assertThat(shot.getDurationSeconds()).isEqualTo(7);
    }
}
