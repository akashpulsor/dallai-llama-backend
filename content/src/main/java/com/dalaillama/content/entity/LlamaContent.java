package com.dalaillama.content.entity;


import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name="llama_content")
public class LlamaContent {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="llama_content_id")
    private long llamaContentId;

    @Column(name="session_id")
    private String sessionId;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    private SearchData searchData;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    private StructureData structureData;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    private Article article;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @PrimaryKeyJoinColumn
    private PublishDestination publishDestination;

    private int userId;
}
