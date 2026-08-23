package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.VideoFeatureFlagDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoFeatureFlagDefinitionRepository extends JpaRepository<VideoFeatureFlagDefinition, String> {

    List<VideoFeatureFlagDefinition> findByActiveTrue();
}
