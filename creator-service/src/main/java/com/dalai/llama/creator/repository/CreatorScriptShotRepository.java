package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorScriptShotRepository extends JpaRepository<CreatorScriptShot, UUID> {

    List<CreatorScriptShot> findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(UUID scriptId);

    void deleteByScriptId(UUID scriptId);
}
