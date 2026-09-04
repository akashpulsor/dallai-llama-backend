package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.CharacterType;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptCharacterRepository extends JpaRepository<ScriptCharacter, UUID> {

    List<ScriptCharacter> findByScriptId(UUID scriptId);

    Optional<ScriptCharacter> findByScriptIdAndCharacterKey(UUID scriptId, String characterKey);

    /** A shot with no {@code primaryCharacterKey} but a non-blank {@code voiceOver} (nobody on
     * screen, a line still needs to be spoken) resolves its speaker here -- "who's actually
     * talking in this beat" when nobody's on screen, per {@link CharacterType}'s own javadoc.
     * Scripts have at most one narrator in practice; {@code findFirst} tolerates more without
     * erroring. */
    Optional<ScriptCharacter> findFirstByScriptIdAndCharacterType(UUID scriptId, CharacterType characterType);
}
