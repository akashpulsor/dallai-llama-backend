package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorScriptCharacterRepository extends JpaRepository<CreatorScriptCharacter, UUID> {

    List<CreatorScriptCharacter> findByScriptIdOrderByCharacterNameAsc(UUID scriptId);

    Optional<CreatorScriptCharacter> findByScriptIdAndCharacterKey(UUID scriptId, String characterKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CreatorScriptCharacter character where character.scriptId = :scriptId")
    int deleteAllByScriptId(@Param("scriptId") UUID scriptId);
}
