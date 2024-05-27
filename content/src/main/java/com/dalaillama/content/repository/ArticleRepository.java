package com.dalaillama.content.repository;

import com.dalaillama.content.entity.Article;
import com.dalaillama.content.entity.Llm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

@Component
public interface ArticleRepository    extends JpaRepository<Article, Integer> {
}
