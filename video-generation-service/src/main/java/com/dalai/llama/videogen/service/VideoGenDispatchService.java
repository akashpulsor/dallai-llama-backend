package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.entity.VideoGenJob;

public interface VideoGenDispatchService {

    DispatchResult dispatch(VideoGenJob job, String positivePrompt, String negativePrompt, VideoDispatchParams params);

    /** Proxies to llm-gateway's already-built {@code POST /v1/jobs/{id}/cancel} -- no new
     * cancellation machinery here (design doc §4.3). */
    void cancel(VideoGenJob job);
}
