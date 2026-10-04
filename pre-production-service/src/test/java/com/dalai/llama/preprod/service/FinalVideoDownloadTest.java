package com.dalai.llama.preprod.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The client can save their film once they have paid for it; before that they can only watch it. */
class FinalVideoDownloadTest {

    private static final String FILM = "https://media.example/film.mp4";

    @Test
    void aPaidClientCanDownloadTheirFilm() {
        assertThat(FinalVideoDownload.url(OffsetDateTime.now(), false, FILM)).isEqualTo(FILM);
    }

    @Test
    void anUnpaidClientCannotUnlessTheCreatorOpenedDownloads() {
        assertThat(FinalVideoDownload.url(null, false, FILM)).isNull();
        assertThat(FinalVideoDownload.url(null, true, FILM)).isEqualTo(FILM);
    }
}
