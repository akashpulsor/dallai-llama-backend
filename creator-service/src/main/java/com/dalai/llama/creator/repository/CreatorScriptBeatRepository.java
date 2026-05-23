package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptBeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorScriptBeatRepository extends JpaRepository<CreatorScriptBeat, UUID> {

    List<CreatorScriptBeat> findByScriptIdOrderByBeatNumberAsc(UUID scriptId);

    void deleteByScriptId(UUID scriptId);
}
