package com.dalaillama.content.entity;


import jakarta.persistence.*;
import lombok.Data;
//import javax.persistence.*;
import java.util.List;

@Data
@Entity
@Table(name="article")
public class Article {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="article_id")
    private int articleId;

    @Column(name="generated_title")
    private String generatedTitle;

    @Column(name="generated_body")
    private String generatedBody;

    @Column(name="body")
    private String body;

    @Column(name="title")
    private String title;

    @Column(name="user_id")
    private int userId;

    @Column(name="external_url")
    private String externalUrl;

    @Enumerated(EnumType.STRING)
    private ContentType contentType;

    @Column(name="favicon_url")
    private String faviconUrl;

    @Column(name="generated_images")
    private List<String> generatedImages;

    @Column(name="search_tags")
    private List<String> searchTags;
}
