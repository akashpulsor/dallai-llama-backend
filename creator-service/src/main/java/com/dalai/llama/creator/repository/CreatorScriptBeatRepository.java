package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptBeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CreatorScriptBeatRepository extends JpaRepository<CreatorScriptBeat, UUID> {

    List<CreatorScriptBeat> findByScriptIdOrderByBeatNumberAsc(UUID scriptId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CreatorScriptBeat beat where beat.scriptId = :scriptId")
    int deleteAllByScriptId(@Param("scriptId") UUID scriptId);
}
