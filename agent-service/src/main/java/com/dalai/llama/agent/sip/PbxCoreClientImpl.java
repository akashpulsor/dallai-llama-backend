package com.dalai.llama.agent.sip;

import com.dalai.llama.agent.dto.OutboundCallRequest;
import com.dalai.llama.agent.dto.SignalingConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PbxCoreClientImpl implements PbxCoreClient {

    private final RestTemplate restTemplate;

    @Value("${pbx.core.base-url}")
    private String pbxCoreUrl;

    @Override
    public SignalingConfigResponse getSignalingConfig(String tenantId) {
        return restTemplate.getForObject(
                pbxCoreUrl + "/v1/signaling/" + tenantId,
                SignalingConfigResponse.class
        );
    }

    public Map<String,Object> startOutboundCall(OutboundCallRequest req) {
        return restTemplate.postForObject(
                pbxCoreUrl + "/api/v1/calls/outbound",
                req,
                Map.class
        );
    }
}
