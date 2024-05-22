package com.dalaillama.content.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatResponseGenerated {
    private String subject;
    private String Summary;

    private JinaResponse response;
    //private List<SearchResponse.TopStories> topStories;
    //List<SearchResponse.OrganicResults> organicResults;
    //private Object twitterResults;

}
