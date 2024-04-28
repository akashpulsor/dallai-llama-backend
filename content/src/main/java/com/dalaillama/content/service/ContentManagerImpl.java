package com.dalaillama.content.service;

import com.dalaillama.content.dto.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

//TODO article repository needs to be implemented
@Component
public class ContentManagerImpl implements ContentManager {

    private ArticleService articleService;
    public ContentManagerImpl(ArticleService articleService) {
        this.articleService = articleService;
    }
    @Override
    public ArticleDto saveArticle(ArticleDto articleDto) {
        return new ArticleDto();
    }

    @Override
    public PublishArticleResponseDto publishArticle(PublishArticleRequestDto publishArticleRequestDto) {
        return new PublishArticleResponseDto();
    }

    @Override
    public WordPressCredentialsResponseDto addWordPressLogin(WordPressCredentialsRequestDto wordPressCredentialsRequestDto) {
        return new WordPressCredentialsResponseDto();
    }

    @Override
    public List<String> generateTags(ArticleDto articleDto) {
        return new ArrayList<>();
    }
}
