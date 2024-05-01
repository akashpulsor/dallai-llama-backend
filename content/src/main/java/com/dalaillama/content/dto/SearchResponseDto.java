package com.dalaillama.content.dto;

import lombok.Data;

@Data
public class SearchResponseDto {

    private SearchResponse.OrganicResults organicResults;

    private Content content;

    private int llamaContentId;

    @Data
    public static class Content {
        private String title;

        private String body;
    }

}
