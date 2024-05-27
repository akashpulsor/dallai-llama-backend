package com.dalaillama.content.service;

import com.dalaillama.content.dto.*;
import com.dalaillama.content.entity.Article;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

public interface ContentManager {

    ArticleDto saveArticle(ArticleDto articleDto);

    PublishArticleResponseDto publishArticle(PublishArticleRequestDto publishArticleRequestDto);

    WordPressCredentialsResponseDto addWordPressLogin(WordPressCredentialsRequestDto wordPressCredentialsRequestDto);

    List<String> generateTags(ArticleDto articleDto);
}
