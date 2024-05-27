package com.dalaillama.content.dto;


import lombok.Data;

@Data
public class PublishArticleRequestDto {

    private int wordpressId;
    private int userId;

    private int articleId;
    private String title;

    private String body;

}
