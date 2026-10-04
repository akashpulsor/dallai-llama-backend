package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.VideoModelGenerationCapability;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoModelGenerationCapabilityRepository extends JpaRepository<VideoModelGenerationCapability, String> {
}
