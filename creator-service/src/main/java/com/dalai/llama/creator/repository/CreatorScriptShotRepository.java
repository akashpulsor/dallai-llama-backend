package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CreatorScriptShotRepository extends JpaRepository<CreatorScriptShot, UUID> {

    List<CreatorScriptShot> findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(UUID scriptId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CreatorScriptShot shot where shot.scriptId = :scriptId")
    int deleteAllByScriptId(@Param("scriptId") UUID scriptId);
}
