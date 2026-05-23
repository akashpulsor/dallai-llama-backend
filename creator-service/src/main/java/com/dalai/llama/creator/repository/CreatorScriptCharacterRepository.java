package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorScriptCharacterRepository extends JpaRepository<CreatorScriptCharacter, UUID> {

    List<CreatorScriptCharacter> findByScriptIdOrderByCharacterNameAsc(UUID scriptId);

    Optional<CreatorScriptCharacter> findByScriptIdAndCharacterKey(UUID scriptId, String characterKey);

    void deleteByScriptId(UUID scriptId);
}
