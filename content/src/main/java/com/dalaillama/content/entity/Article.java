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

    @Column(name="title")
    private String title;

    @Column(name="body")
    private String body;

    @Column(name="user_id")
    private int userId;

    @Column(name="structure_id")
    private int structureId;

    @Column(name="generation_id")
    private int generationId;

    @Column(name="is_published")
    private boolean isPublished;

    @Column(name="publish_id")
    private String publishId;

    @Column(name="search_tags")
    private List<String> searchTags;
}
