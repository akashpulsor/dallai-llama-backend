package com.dalaillama.content.service;

import com.dalaillama.content.entity.Article;

public interface ArticleService {

    Article saveArticle();

    Article getArticle(int articleId);

}
