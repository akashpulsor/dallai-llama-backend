package com.dalai.llama.pbx.core.dto;

import lombok.Data;

@Data
public class ListenRequest {
    private String rpcUrl;
    private String supervisorCallId;
    private String supervisorContact;
}
