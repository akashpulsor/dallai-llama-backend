package com.dalai.llama.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SipBootstrapResponse {

    /** WSS URL for SIP over WebSocket */
    private String sipUrl;

    /** Agent SIP username (usually extension) */
    private String sipUsername;

    /** Agent SIP password */
    private String sipPassword;

    /** SIP authentication realm (tenant-level) */
    private String realm;

    /** Pretty display name for caller ID */
    private String displayName;
}
