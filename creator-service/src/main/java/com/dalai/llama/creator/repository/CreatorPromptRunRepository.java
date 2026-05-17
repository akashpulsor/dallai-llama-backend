package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CreatorPromptRunRepository extends JpaRepository<CreatorPromptRun, UUID> {
}
