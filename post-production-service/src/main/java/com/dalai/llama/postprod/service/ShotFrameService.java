package com.dalai.llama.postprod.service;


import com.dalai.llama.postprod.dto.ShotFrameExtractionResult;
import com.dalai.llama.postprod.dto.ShotView;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShotFrameService {
   //TODO: This service should be refactored to be more testable. Currently, it is tightly coupled with the PreProductionClient and FfmpegClipProcessor, making it difficult to test in isolation.
    private final PreProductionClient preProductionClient;
    private final FfmpegClipProcessor ffmpeg;

    public ShotFrameExtractionResult extractFrames(
            UUID tenantId,
            UUID shotId) {


        ShotView shotData = preProductionClient.getShot(tenantId, shotId);

        String url = shotData.videoUrl();
        int fps = shotData.fps();
        ffmpeg.extractFrames(url, fps, frame -> {
            // Upload frame
            // Save ShotFrame through pre-production
        });

        // 1. Get shot
        // 2. Get video URL
        // 3. Get FPS
        // 4. Call ffmpeg.extractFrames(...)
        // 5. For every emitted frame:
        //      upload frame
        //      save ShotFrame through pre-production

        return new ShotFrameExtractionResult(shotId, 1);
    }
}