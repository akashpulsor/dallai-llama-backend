package com.dalai.llama.postprod.service.clip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A film is upscaled until its short side is full HD, never past 4x, and never twice. */
class FilmUpscaleFactorTest {

    @Test
    void aVerticalFilmIsUpscaledToFullHd() {
        assertThat(FilmUpscaleService.factor(480, 854)).isEqualByComparingTo("2.25");
        assertThat(FilmUpscaleService.factor(1280, 720)).isEqualByComparingTo("1.50");
        assertThat(FilmUpscaleService.factor(200, 300)).isEqualByComparingTo("4.00");
    }

    @Test
    void aFilmAlreadyFullHdOrOfUnknownSizeIsRefused() {
        assertThatThrownBy(() -> FilmUpscaleService.factor(1080, 1920)).hasMessageContaining("already full HD");
        assertThatThrownBy(() -> FilmUpscaleService.factor(null, 1920)).hasMessageContaining("size is unknown");
    }
}
