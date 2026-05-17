package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreatorPromptTemplateRepository extends JpaRepository<CreatorPromptTemplate, UUID> {

    Optional<CreatorPromptTemplate> findTopByTemplateKeyAndStatusOrderByVersionDesc(
            String templateKey,
            String status
    );
}
