package com.dalai.llama.agent.sip;

import com.dalai.llama.agent.dto.OutboundCallRequest;
import com.dalai.llama.agent.dto.SignalingConfigResponse;

import java.util.Map;

public interface PbxCoreClient {
    SignalingConfigResponse getSignalingConfig(String tenantId);

    Map<String,Object> startOutboundCall(OutboundCallRequest req);
}
