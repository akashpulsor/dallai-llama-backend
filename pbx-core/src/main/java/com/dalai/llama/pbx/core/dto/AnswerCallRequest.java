package com.dalai.llama.pbx.core.dto;

import lombok.Data;

@Data
public class AnswerCallRequest {
    private String rpcUrl;
    private String fromTag;
    private String toTag;
    private String sdpAnswer;
}
