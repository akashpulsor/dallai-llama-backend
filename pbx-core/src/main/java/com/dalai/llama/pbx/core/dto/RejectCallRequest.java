package com.dalai.llama.pbx.core.dto;

import lombok.Data;

@Data
public class RejectCallRequest {
    private String rpcUrl;
    private String fromTag;
    private int code;
    private String reason;
}
