package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptCharacterRepository extends JpaRepository<ScriptCharacter, UUID> {

    List<ScriptCharacter> findByScriptId(UUID scriptId);

    Optional<ScriptCharacter> findByScriptIdAndCharacterKey(UUID scriptId, String characterKey);
}
