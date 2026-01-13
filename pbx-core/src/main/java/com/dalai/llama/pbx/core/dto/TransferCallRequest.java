package com.dalai.llama.pbx.core.dto;

import lombok.Data;

@Data
public class TransferCallRequest {
    private String rpcUrl;
    private String fromTag;
    private String toTag;
    private String destination;
}
