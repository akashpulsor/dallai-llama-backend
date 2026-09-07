package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CharacterType;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Shared "who actually speaks this shot's line" rule, used everywhere a beat's speaker gets
 * resolved without an explicit override ({@link ShotDialogueBeatService}, {@link
 * DialogueDetailsService}) -- previously duplicated inline in both. A shot's own primary character
 * speaks unless it's a mute PRODUCT (a product doesn't talk; any narration about it is the
 * narrator), or there's no primary character at all (nobody on screen); both cases fall through to
 * the script's narrator, if one exists. */
@Component
public class EffectiveSpeakerResolver {

    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;

    public EffectiveSpeakerResolver(ScriptRepository scriptRepository, ScriptCharacterRepository scriptCharacterRepository) {
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
    }

    public String resolveCharacterKey(UUID projectId, Shot shot) {
        Script script = scriptRepository.findByProjectId(projectId).orElse(null);
        if (script == null) {
            return null;
        }
        String primaryKey = shot.getPrimaryCharacterKey();
        if (primaryKey != null && !isMuteProduct(script, primaryKey)) {
            return primaryKey;
        }
        return scriptCharacterRepository.findFirstByScriptIdAndCharacterType(script.getId(), CharacterType.NARRATOR)
                .map(ScriptCharacter::getCharacterKey)
                .orElse(null);
    }

    private boolean isMuteProduct(Script script, String characterKey) {
        return scriptCharacterRepository.findByScriptIdAndCharacterKey(script.getId(), characterKey)
                .map(character -> character.getCharacterType() == CharacterType.PRODUCT)
                .orElse(false);
    }
}
