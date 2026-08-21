package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.Script;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScriptRepository extends JpaRepository<Script, UUID> {

    Optional<Script> findByProjectId(UUID projectId);
}
