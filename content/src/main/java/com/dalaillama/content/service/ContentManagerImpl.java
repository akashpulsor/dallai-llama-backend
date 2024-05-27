package com.dalaillama.content.service;

import com.dalaillama.content.dto.*;
import com.dalaillama.content.entity.Article;
import com.dalaillama.content.entity.LlamaContent;
import dev.langchain4j.data.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

//TODO article repository needs to be implemented
@Component
public class ContentManagerImpl implements ContentManager {

    private final ArticleService articleService;

    private final LlamaContentService llamaContentService;
    public ContentManagerImpl(ArticleService articleService, LlamaContentService llamaContentService) {
        this.articleService = articleService;
        this.llamaContentService = llamaContentService;
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

    public void loadJson(Article article){
        //Document document = new Document();
    }

}
