package com.dalaillama.content.dto;

import lombok.Data;

import java.util.List;

@Data
public class ArticleDto {
    private int userId;
    private int structureId;
    private String articleStructureMetadata;
    private int searchId;
    private int llmId;
    private int toolId;
    private int llamaContentId;
    private String title;
    private String body;
    private List<String> generateTags;
}
