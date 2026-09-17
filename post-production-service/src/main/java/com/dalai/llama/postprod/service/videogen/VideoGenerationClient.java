package com.dalai.llama.postprod.service.videogen;

import java.util.List;
import java.util.UUID;

/** The only way this service learns about generated shots -- it never talks to fal.ai/llm-gateway
 * for video, and never reads video-generation-service's database directly. */
public interface VideoGenerationClient {

    /** Latest job per shot_ref for the project -- used by the PROJECT-scope flow ("move the whole
     * finished video to post-production") to discover every shot without the caller already
     * knowing the shot_ref list. */
    List<VideoGenShotJob> listJobsForProject(UUID tenantId, UUID projectId);

    /** A short-lived signed URL for the generated clip, resolved from video-generation-service's
     * own {@code GET /v1/jobs/{jobId}/video} redirect. */
    String getShotVideoUrl(UUID tenantId, UUID videoGenJobId);

    /** The shot's current clip and the recorded take that belongs on it -- what post-production
     * needs to make a new cut without reading the other service's database. */
    com.dalai.llama.postprod.service.clip.ShotClipSource getClipSource(UUID tenantId, UUID projectId, UUID shotId);
}
