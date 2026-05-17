package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorConnectorRunDiff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorConnectorRunDiffRepository extends JpaRepository<CreatorConnectorRunDiff, UUID> {

    List<CreatorConnectorRunDiff> findTop20ByOrderByCreatedAtDesc();
}
