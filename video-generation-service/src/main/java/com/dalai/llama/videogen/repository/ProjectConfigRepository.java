package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ProjectConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, UUID> {
}
