package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.FoleyCue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FoleyCueRepository extends JpaRepository<FoleyCue, Long> {

    List<FoleyCue> findByPromptIdOrderByTimestampMsAsc(UUID promptId);
}
