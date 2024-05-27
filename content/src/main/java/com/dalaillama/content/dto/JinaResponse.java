package com.dalaillama.content.dto;

import lombok.Data;

import java.util.List;
@Data
public class JinaResponse {
    private String code;
    private String status;

    private List<ContentData> data;

    @Data
    public static class ContentData {
        private String url;
        private String title;
        private String description;
    }

}
