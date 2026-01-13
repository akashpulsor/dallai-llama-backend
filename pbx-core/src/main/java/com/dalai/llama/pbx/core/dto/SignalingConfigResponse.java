package com.dalai.llama.pbx.core.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SignalingConfigResponse {

    private String tenantId;

    /** SIP authentication realm */
    private String authRealm;

    /** SIP over WebSocket endpoint (WSS URL) */
    private String wssUrl;

    /** SIP FQDN used as outbound proxy */
    private String sipFqdn;

    /** Optional WebRTC/NAT traversal servers */
    private List<String> stunUrls;
    private List<String> turnUrls;

    /** Whether TURN / RTPengine / SBC are enabled */
    private boolean enableTurn;
    private boolean enableRtpEngine;
    private boolean sbcEnabled;
}
