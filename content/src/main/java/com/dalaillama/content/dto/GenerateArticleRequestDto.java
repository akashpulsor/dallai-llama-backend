package com.dalaillama.content.dto;

import lombok.Data;

@Data
public class GenerateArticleRequestDto {
    private int llmId;
    private GenerateStructureRequestDto structure;
    private String topicName;
}
