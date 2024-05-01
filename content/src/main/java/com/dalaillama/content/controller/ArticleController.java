package com.dalaillama.content.controller;

import com.dalaillama.content.dto.*;
import com.dalaillama.content.service.ContentManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/article")
public class ArticleController {

    private final ContentManager contentManager;
    public ArticleController(ContentManager contentManager) {
        this.contentManager = contentManager;
    }
    @PostMapping("/save")
    public ArticleDto saveArticle(@RequestBody ArticleDto articleDto) {
        return this.contentManager.saveArticle(articleDto);
    }

    @PostMapping("/publish")
    public PublishArticleResponseDto publishArticle(@RequestBody PublishArticleRequestDto publishArticleRequestDto) {
        return this.contentManager.publishArticle(publishArticleRequestDto);
    }

    @PostMapping("/login/wordpress")
    public WordPressCredentialsResponseDto addWordPressLogin(@RequestBody WordPressCredentialsRequestDto wordPressCredentialsRequestDto) {
        return this.contentManager.addWordPressLogin(wordPressCredentialsRequestDto);
    }

    @PostMapping("/generateTags")
    public List<String> saveGenerateTags(@RequestBody ArticleDto articleDto) {
        return this.contentManager.generateTags(articleDto);
    }
}

/*
* if0_36467687
* sql108.infinityfree.com
* if0_36467687_dalai_llama
* 4xjqv70g
*
* */