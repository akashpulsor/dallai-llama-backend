package com.dalaillama.content.dto;

import lombok.Data;

@Data
public class GenerateStructureRequestDto {
    //topicName, llmId, userId
    private String topicName;
    private int llmId;
    private int userId;
}
