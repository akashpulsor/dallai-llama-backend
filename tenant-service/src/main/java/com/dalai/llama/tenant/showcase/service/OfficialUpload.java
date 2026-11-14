package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.youtube.client.YouTubeUploadClient;

import java.math.BigDecimal;
import java.util.function.Consumer;

/** One film ready to go on Dalaillama's channel: what to send YouTube, the film's own facts (they
 * stand in until YouTube lists the upload), and what to do with the new video id. A project's film
 * and a creator's upload each build one; OfficialUploadWorker sends it. */
record OfficialUpload(YouTubeUploadClient.UploadRequest request, Facts facts, Consumer<String> complete) {

    record Facts(String title, BigDecimal durationSeconds, Integer width, Integer height) {
    }
}
