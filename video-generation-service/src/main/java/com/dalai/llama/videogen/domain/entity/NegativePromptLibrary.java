package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.LibraryScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "negative_prompt_library")
public class NegativePromptLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snippet_id")
    private Long snippetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LibraryScope scope;

    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> tags;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
