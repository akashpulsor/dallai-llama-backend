package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboardScene;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorStoryboardSceneRepository extends JpaRepository<CreatorStoryboardScene, UUID> {

    List<CreatorStoryboardScene> findByStoryboardIdOrderByShotNumberAsc(UUID storyboardId);
}
